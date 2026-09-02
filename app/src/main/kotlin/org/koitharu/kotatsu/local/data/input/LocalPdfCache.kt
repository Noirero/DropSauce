package org.koitharu.kotatsu.local.data.input

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import okhttp3.internal.platform.PlatformRegistry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Renders local PDF pages into the app cache without modifying the source PDF.
 * Cache identity includes file path, size and modified time so replaced PDFs get fresh pages.
 */
object LocalPdfCache {

	private const val CACHE_DIR_NAME = "local_pdf_pages"
	// Rendering every page at 4x/4096px made long local PDFs spend tens of seconds in PNG deflate,
	// increased GC pressure and could contribute to foreground ANRs while the library UI was active.
	// 2560px is still comfortably above typical phone display resolution while cutting bitmap area,
	// memory use and PNG compression work substantially.
	private const val PDF_RENDER_SCALE = 2.5f
	private const val MAX_RENDER_DIMENSION = 2560

	@Synchronized
	fun renderCover(pdf: File): File? = runCatching {
		openRenderer(pdf) { renderer ->
			if (renderer.pageCount <= 0) {
				return@openRenderer null
			}
			renderPage(renderer, pageIndex = 0, outputDir = cacheDirFor(pdf))
		}
	}.getOrNull()

	@Synchronized
	fun renderPages(pdf: File): List<File> {
		return openRenderer(pdf) { renderer ->
			if (renderer.pageCount <= 0) {
				throw IOException("PDF has no pages: $pdf")
			}
			val outputDir = cacheDirFor(pdf)
			List(renderer.pageCount) { index ->
				renderPage(renderer, index, outputDir)
			}
		}
	}

	private inline fun <T> openRenderer(pdf: File, block: (PdfRenderer) -> T): T {
		if (!pdf.isFile || !pdf.canRead()) {
			throw IOException("Cannot read PDF: $pdf")
		}
		return ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
			PdfRenderer(descriptor).use(block)
		}
	}

	private fun renderPage(renderer: PdfRenderer, pageIndex: Int, outputDir: File): File {
		val outputFile = File(outputDir, "page_${(pageIndex + 1).toString().padStart(5, '0')}.png")
		if (outputFile.isFile && outputFile.length() > 0L) {
			return outputFile
		}
		if (!outputDir.exists() && !outputDir.mkdirs()) {
			throw IOException("Cannot create PDF cache directory: $outputDir")
		}

		val tempFile = File(outputDir, outputFile.name + ".tmp")
		renderer.openPage(pageIndex).use { page ->
			val maxPageSize = maxOf(page.width, page.height).coerceAtLeast(1)
			val scale = minOf(PDF_RENDER_SCALE, MAX_RENDER_DIMENSION / maxPageSize.toFloat())
			val matrix = Matrix().apply { setScale(scale, scale) }
			val width = (page.width * scale).roundToInt().coerceAtLeast(1)
			val height = (page.height * scale).roundToInt().coerceAtLeast(1)
			val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
			try {
				bitmap.eraseColor(Color.WHITE)
				page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
				tempFile.outputStream().buffered().use { output ->
					if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
						throw IOException("Cannot encode rendered PDF page $pageIndex")
					}
				}
			} finally {
				bitmap.recycle()
			}
		}

		if (outputFile.exists() && !outputFile.delete()) {
			tempFile.delete()
			throw IOException("Cannot replace PDF cache page: $outputFile")
		}
		if (!tempFile.renameTo(outputFile)) {
			tempFile.copyTo(outputFile, overwrite = true)
			tempFile.delete()
		}
		return outputFile
	}

	private fun cacheDirFor(pdf: File): File {
		val context = checkNotNull(PlatformRegistry.applicationContext) {
			"Application context is not initialized"
		}
		return File(File(context.cacheDir, CACHE_DIR_NAME), cacheKey(pdf))
	}

	private fun cacheKey(pdf: File): String {
		val path = runCatching { pdf.canonicalPath }.getOrDefault(pdf.absolutePath)
		val identity = "$path\u0000${pdf.length()}\u0000${pdf.lastModified()}"
		val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
		return buildString(digest.size * 2) {
			for (byte in digest) {
				append(((byte.toInt() ushr 4) and 0xF).toString(16))
				append((byte.toInt() and 0xF).toString(16))
			}
		}
	}
}
