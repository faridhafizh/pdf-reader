package com.faridhafizh.pdfreader.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.faridhafizh.pdfreader.data.SignatureEntity
import com.faridhafizh.pdfreader.data.SignaturePlacementEntity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min
import kotlin.math.max

/**
 * Embeds signature placements into the PDF file as image XObjects (readable by other viewers).
 * Pattern matches AnnotationWriter: temp file -> verify it opens -> atomic move over original.
 *
 * Rotation is baked into a copy of the signature bitmap so drawing stays a simple
 * non-rotated drawImage (works on every viewer, no content-stream rotation needed).
 * Deletions are covered with a white rectangle.
 */
class SignatureWriter {

    /** Blocking. [signatures] maps signature id -> PNG file. */
    fun apply(
        file: File,
        add: List<Pair<SignaturePlacementEntity, SignatureEntity>>,
        remove: List<SignaturePlacementEntity>,
        signatures: Map<String, File>,
    ) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            PDDocument.load(file).use { doc ->
                for (p in remove) coverPlacement(doc, p)
                for ((p, sig) in add) {
                    val imgFile = signatures[p.signatureId] ?: continue
                    drawPlacement(doc, p, imgFile)
                }
                doc.save(tmp)
            }
            PDDocument.load(tmp).use { check(it.numberOfPages > 0) { "Written PDF has no pages" } }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun drawPlacement(doc: PDDocument, p: SignaturePlacementEntity, imgFile: File) {
        if (p.page !in 0 until doc.numberOfPages) return
        val page = doc.getPage(p.page)
        val sigBitmap = BitmapFactory.decodeFile(imgFile.absolutePath) ?: return
        val rotated: Bitmap = if (p.rotationDeg % 360f == 0f) sigBitmap else rotateBitmap(sigBitmap, p.rotationDeg)
        val xObject: PDImageXObject = LosslessFactory.createFromImage(doc, rotated)
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val displayW = displayWidthPts(box, rot)
        val (cx, cy) = toUser(box, rot, p.centerX, p.centerY)
        val widthPts = p.widthFrac * displayW
        val heightPts = if (rotated.width > 0) widthPts * rotated.height / rotated.width else widthPts
        // drawImage(Matrix) maps the unit square to the target rect; build final matrix directly:
        // (0..1,0..1) -> (cx-w/2, cy-h/2, w, h) anchored at its bottom-left.
        val m = Matrix(
            widthPts, 0f, 0f, heightPts,
            cx - widthPts / 2f, cy - heightPts / 2f,
        )
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
            cs.drawImage(xObject, m)
        }
        // Note: rotated bitmap (if any) is reclaimed by GC; LosslessFactory copies pixels.
    }

    private fun coverPlacement(doc: PDDocument, p: SignaturePlacementEntity) {
        if (p.page !in 0 until doc.numberOfPages) return
        val page = doc.getPage(p.page)
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val (x1, y1) = toUser(box, rot, p.centerX - p.widthFrac / 2f, p.centerY - 0.15f)
        val (x2, y2) = toUser(box, rot, p.centerX + p.widthFrac / 2f, p.centerY + 0.15f)
        val left = min(x1, x2) - 4f
        val right = max(x1, x2) + 4f
        val bottom = min(y1, y2) - 4f
        val top = max(y1, y2) + 4f
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
            cs.setNonStrokingColor(
                PDColor(floatArrayOf(1f, 1f, 1f), PDDeviceRGB.INSTANCE)
            )
            cs.fillRect(left, bottom, right - left, top - bottom)
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (out !== source) source.recycle()
        return out
    }

    private fun displayWidthPts(box: PDRectangle, rot: Int): Float = when (rot) {
        90, 270 -> box.height
        else -> box.width
    }

    /** Display fractions (top-left origin, rotation applied) -> PDF user space (bottom-left origin). */
    private fun toUser(box: PDRectangle, rot: Int, fx: Float, fy: Float): Pair<Float, Float> {
        val w = box.width; val h = box.height
        val l = box.lowerLeftX; val b = box.lowerLeftY
        val r = box.upperRightX; val t = box.upperRightY
        return when (rot) {
            90 -> (l + fy * w) to (b + fx * h)
            180 -> (r - fx * w) to (b + fy * h)
            270 -> (r - fy * w) to (t - fx * h)
            else -> (l + fx * w) to (t - fy * h)
        }
    }
}
