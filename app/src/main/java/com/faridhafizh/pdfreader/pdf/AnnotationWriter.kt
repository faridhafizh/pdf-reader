package com.faridhafizh.pdfreader.pdf

import com.faridhafizh.pdfreader.data.AnnType
import com.faridhafizh.pdfreader.data.AnnotationEntity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min

/**
 * Embeds highlight/underline annotations into the PDF file (standard /Highlight and /Underline
 * annotations, readable by other PDF viewers). Each annotation's /NM entry holds the app id so it can be removed later.
 */
class AnnotationWriter {

    /** Blocking. Writes to a temp file, verifies it opens, then atomically replaces the original. */
    fun apply(file: File, add: List<AnnotationEntity>, removeIds: Set<String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            PDDocument.load(file).use { doc ->
                if (removeIds.isNotEmpty()) {
                    for (page in doc.pages) {
                        val list = page.annotations
                        if (list.removeAll { it.annotationName in removeIds }) page.annotations = list
                    }
                }
                for (a in add) addOne(doc, a)
                doc.save(tmp)
            }
            PDDocument.load(tmp).use { check(it.numberOfPages > 0) { "Written PDF has no pages" } }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun addOne(doc: PDDocument, a: AnnotationEntity) {
        if (a.page !in 0 until doc.numberOfPages) return
        val page = doc.getPage(a.page)
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360

        val subtype = if (a.type == AnnType.UNDERLINE) PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
        else PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
        val ann = PDAnnotationTextMarkup(subtype)

        val quads = ArrayList<Float>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (r in a.rects()) {
            val (x1, y1) = toUser(box, rot, r.l, r.t)
            val (x2, y2) = toUser(box, rot, r.r, r.b)
            val left = min(x1, x2); val right = max(x1, x2)
            val bottom = min(y1, y2); val top = max(y1, y2)
            // Order used by Acrobat and most viewers: top-left, top-right, bottom-left, bottom-right
            quads += listOf(left, top, right, top, left, bottom, right, bottom)
            minX = min(minX, left); maxX = max(maxX, right)
            minY = min(minY, bottom); maxY = max(maxY, top)
        }
        if (quads.isEmpty()) return

        ann.quadPoints = quads.toFloatArray()
        ann.rectangle = PDRectangle(minX, minY, maxX - minX, maxY - minY)
        val c = a.color
        ann.color = PDColor(
            floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f),
            PDDeviceRGB.INSTANCE,
        )
        ann.annotationName = a.id
        if (a.type == AnnType.HIGHLIGHT) ann.constantOpacity = 0.4f
        ann.isPrinted = true
        try { ann.constructAppearances() } catch (_: Exception) { /* viewers can still draw from QuadPoints */ }

        val list = page.annotations
        list.add(ann)
        page.annotations = list
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
