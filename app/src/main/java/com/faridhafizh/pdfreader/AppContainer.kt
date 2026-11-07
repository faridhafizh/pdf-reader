package com.faridhafizh.pdfreader

import android.content.Context
import androidx.room.Room
import com.faridhafizh.pdfreader.data.AppDatabase
import com.faridhafizh.pdfreader.data.DocumentRepository
import com.faridhafizh.pdfreader.data.MIGRATION_1_2
import com.faridhafizh.pdfreader.pdf.AnnotationWriter
import com.faridhafizh.pdfreader.pdf.SignatureWriter
import com.faridhafizh.pdfreader.pdf.ThumbnailLoader
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI framework). */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Survives screens/ViewModels; used for background saves when leaving the reader. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "pdfreader.db")
        .addMigrations(MIGRATION_1_2)
        .build()
    val pdfiumCore = PdfiumCore(app)
    val repository = DocumentRepository(app, database, pdfiumCore, AnnotationWriter(), SignatureWriter())
    val thumbnails = ThumbnailLoader(pdfiumCore)
}
