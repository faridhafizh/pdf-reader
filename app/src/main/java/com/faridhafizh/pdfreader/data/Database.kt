package com.faridhafizh.pdfreader.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object AnnState {
    const val PENDING_ADD = "PENDING_ADD"       // only in the app database
    const val SYNCED = "SYNCED"                 // also embedded in the PDF file
    const val PENDING_DELETE = "PENDING_DELETE" // embedded in the PDF, must be removed on next save
}

object AnnType {
    const val HIGHLIGHT = "HIGHLIGHT"
    const val UNDERLINE = "UNDERLINE"
}

/** Signature image stored as a PNG file in `filesDir/signatures`. */
@Entity(tableName = "signatures")
data class SignatureEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
)

/**
 * A signature placed on one page of one document.
 * [centerX]/[centerY] are fractions (0..1) of the displayed page (center, top-left origin),
 * [widthFrac] is the image width as a fraction of page width, [rotationDeg] clockwise degrees.
 * [state] reuses [AnnState] semantics: PENDING_ADD (overlay only), SYNCED (also embedded),
 * PENDING_DELETE (embedded, must be removed on next save).
 */
@Entity(
    tableName = "signature_placements",
    foreignKeys = [ForeignKey(DocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId"), Index("signatureId")],
)
data class SignaturePlacementEntity(
    @PrimaryKey val id: String,
    val documentId: Long,
    val signatureId: String,
    val page: Int,
    val centerX: Float,
    val centerY: Float,
    val widthFrac: Float,
    val rotationDeg: Float,
    val state: String,
    val createdAt: Long,
)

/** Rectangle as fractions (0..1) of the displayed page, origin top-left. Resolution independent. */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)

fun List<NRect>.encodeRects(): String = joinToString(";") { "${it.l},${it.t},${it.r},${it.b}" }

fun String.decodeRects(): List<NRect> = split(";").filter { it.isNotBlank() }.map { s ->
    val p = s.split(",").map { it.toFloat() }
    NRect(p[0], p[1], p[2], p[3])
}

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val fileName: String,
    val pageCount: Int,
    val lastPage: Int = 0,
    val lastOpened: Long,
    val addedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(DocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val page: Int,
    val label: String,
    val createdAt: Long,
)

@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(DocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val documentId: Long,
    val page: Int,
    val type: String,
    val color: Int,
    val rectsData: String,
    val state: String,
    val createdAt: Long,
) {
    fun rects(): List<NRect> = rectsData.decodeRects()
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastOpened DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): DocumentEntity?

    @Insert
    suspend fun insert(d: DocumentEntity): Long

    @Query("UPDATE documents SET lastPage = :page, lastOpened = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, now: Long)

    @Delete
    suspend fun delete(d: DocumentEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE documentId = :docId ORDER BY page")
    fun observe(docId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE documentId = :docId AND page = :page")
    suspend fun countAt(docId: Long, page: Int): Int

    @Query("DELETE FROM bookmarks WHERE documentId = :docId AND page = :page")
    suspend fun deleteAt(docId: Long, page: Int)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE documentId = :docId AND state != 'PENDING_DELETE'")
    fun observeVisible(docId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT COUNT(*) FROM annotations WHERE documentId = :docId AND state != 'SYNCED'")
    fun observePendingCount(docId: Long): Flow<Int>

    @Query("SELECT * FROM annotations WHERE documentId = :docId AND state != 'SYNCED'")
    suspend fun pending(docId: Long): List<AnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: AnnotationEntity)

    @Query("UPDATE annotations SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures WHERE id = :id")
    suspend fun get(id: String): SignatureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SignatureEntity)

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SignaturePlacementDao {
    @Query(
        "SELECT * FROM signature_placements WHERE documentId = :docId AND state != 'PENDING_DELETE' ORDER BY createdAt",
    )
    fun observeVisible(docId: Long): Flow<List<SignaturePlacementEntity>>

    @Query("SELECT COUNT(*) FROM signature_placements WHERE documentId = :docId AND state != 'SYNCED'")
    fun observePendingCount(docId: Long): Flow<Int>

    @Query("SELECT * FROM signature_placements WHERE documentId = :docId AND state != 'SYNCED'")
    suspend fun pending(docId: Long): List<SignaturePlacementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: SignaturePlacementEntity)

    @Query("UPDATE signature_placements SET centerX = :cx, centerY = :cy, widthFrac = :wf, rotationDeg = :rot WHERE id = :id")
    suspend fun updateTransform(id: String, cx: Float, cy: Float, wf: Float, rot: Float)

    @Query("UPDATE signature_placements SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("DELETE FROM signature_placements WHERE id = :id")
    suspend fun delete(id: String)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signatures` (" +
                "`id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `widthPx` INTEGER NOT NULL, " +
                "`heightPx` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signature_placements` (" +
                "`id` TEXT NOT NULL, `documentId` INTEGER NOT NULL, `signatureId` TEXT NOT NULL, " +
                "`page` INTEGER NOT NULL, `centerX` REAL NOT NULL, `centerY` REAL NOT NULL, " +
                "`widthFrac` REAL NOT NULL, `rotationDeg` REAL NOT NULL, `state` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_signature_placements_documentId` " +
                "ON `signature_placements` (`documentId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_signature_placements_signatureId` " +
                "ON `signature_placements` (`signatureId`)",
        )
    }
}

@Database(
    entities = [DocumentEntity::class, BookmarkEntity::class, AnnotationEntity::class, SignatureEntity::class, SignaturePlacementEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun signatureDao(): SignatureDao
    abstract fun signaturePlacementDao(): SignaturePlacementDao
}
