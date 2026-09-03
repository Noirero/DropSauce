package org.koitharu.kotatsu.extensions.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalExtensionLanguageFlagTest {

	@Test
	fun `language codes use expected flags`() {
		assertEquals("🇮🇩", getExternalExtensionLanguageFlag("id"))
		assertEquals("🇨🇳", getExternalExtensionLanguageFlag("zh"))
		assertEquals("🇬🇧", getExternalExtensionLanguageFlag("en"))
	}

	@Test
	fun `region overrides the language default`() {
		assertEquals("🇧🇷", getExternalExtensionLanguageFlag("pt-BR"))
		assertEquals("🇹🇼", getExternalExtensionLanguageFlag("zh_TW"))
	}

	@Test
	fun `non-country groups have neutral symbols`() {
		assertEquals("🌐", getExternalExtensionLanguageFlag("all"))
		assertEquals("🏳️", getExternalExtensionLanguageFlag("other"))
	}
}
