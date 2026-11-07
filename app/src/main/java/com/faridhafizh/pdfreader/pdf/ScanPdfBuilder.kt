package com.faridhafizh.pdfreader.pdf

import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Builds a PDF from a list of scanned photos (each one becomes one page so the
 * scan is stored exactly as shot). Page size matches the photo aspect ratio,
 * scaled to A4 width points. Blocking; call from a background dispatcher.
 */
object ScanPdfBuilder {

    fun build(images: List<File>, output: File): Int {
        val tmp = File(output.parentFile, output.name + ".tmp")
        try {
            PDDocument().use { doc ->
                for (f in images) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, bounds)
                    val iw = bounds.outWidth.coerceAtLeast(1)
                    val ih = bounds.outHeight.coerceAtLeast(1)
                    val widthPts = 595f // A4 width in points
                    val heightPts = widthPts * ih / iw
                    // Clamp very tall panoramas to keep pages reasonable.
                    val page = PDPage(PDRectangle(widthPts, heightPts.coerceIn(widthPts * 0.5f, widthPts * 3f)))
                    doc.addPage(page)
                    val xObject = PDImageXObject.createFromFileByContent(f, doc)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(xObject, 0f, 0f, widthPts, heightPts)
                    }
                }
                check(doc.numberOfPages > 0) { "No scan pages" }
                doc.save(tmp)
            }
            PDDocument.load(tmp).use { check(it.numberOfPages > 0) { "Built PDF has no pages" } }
            Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return images.size
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
