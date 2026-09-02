package org.koitharu.kotatsu.extensions.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalExtensionRuntimeTest {

	@Test
	fun `pseudo languages use stable labels`() {
		assertEquals("All", getExternalExtensionLanguageDisplayName("all"))
		assertEquals("Other", getExternalExtensionLanguageDisplayName("other"))
	}

	@Test
	fun `one package keeps same-name language sources separate by id`() {
		val result = process(listOf(
			Success("extension.nhentai", listOf(
				Source(11, "NHentai", "en"),
				Source(12, "NHentai", "ru"),
				Source(13, "NHentai", "all"),
				Source(14, "NHentai", "other"),
			)),
		))

		assertEquals(setOf(11L, 12L, 13L, 14L), result.wrappedSourceById.keys)
		assertEquals(listOf("en", "ru", "all", "other"), result.wrappedSourceById.values.map { it.lang })
		assertTrue(result.wrappedSourceById.values.all { it.hasLanguageSiblings })
	}

	@Test
	fun `same source name in different packages is not treated as language siblings`() {
		val result = process(listOf(
			Success("extension.first", listOf(Source(21, "Reader", "en"))),
			Success("extension.second", listOf(Source(22, "Reader", "ja"))),
		))

		assertFalse(result.wrappedSourceById.getValue(21).hasLanguageSiblings)
		assertFalse(result.wrappedSourceById.getValue(22).hasLanguageSiblings)
	}

	@Test
	fun `arbitrary language code remains source metadata`() {
		val result = process(listOf(
			Success("extension.future", listOf(Source(31, "Future Source", "x-future"))),
		))

		assertEquals("x-future", result.wrappedSourceById.getValue(31).lang)
	}

	private fun process(results: List<Result>) = processExternalExtensionResults(
		results = results,
		successOf = { it as? Success },
		errorOf = { it as? Error },
		untrustedPackageNameOf = { null },
		successSources = { it.sources },
		successPackageName = { it.pkgName },
		successIsNsfw = { false },
		sourceId = { it.id },
		asCatalogueSource = { it },
		catalogueSourceName = { it.name },
		catalogueSourceLang = { it.lang },
		buildWrappedSource = { source, pkgName, _, hasLanguageSiblings ->
			WrappedSource(source.id, pkgName, source.lang, hasLanguageSiblings)
		},
	)

	private sealed interface Result
	private data class Success(val pkgName: String, val sources: List<Source>) : Result
	private object Error : Result
	private data class Source(val id: Long, val name: String, val lang: String)
	private data class WrappedSource(
		val id: Long,
		val pkgName: String,
		val lang: String,
		val hasLanguageSiblings: Boolean,
	)
}
