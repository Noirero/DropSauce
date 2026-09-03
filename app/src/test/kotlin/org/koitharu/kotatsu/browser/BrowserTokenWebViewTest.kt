package org.koitharu.kotatsu.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserTokenWebViewTest {

	@Test
	fun `recognizes SchaleNetwork name variants`() {
		assertTrue(requiresUnfilteredTokenWebView("SchaleNetwork", "example.Source"))
		assertTrue(requiresUnfilteredTokenWebView("Schale Network", "example.Source"))
		assertTrue(requiresUnfilteredTokenWebView("Schale-Network", "example.Source"))
	}

	@Test
	fun `recognizes SchaleNetwork and Koharu packages`() {
		assertTrue(requiresUnfilteredTokenWebView("Mirror", "extensions.schalenetwork.Source"))
		assertTrue(requiresUnfilteredTokenWebView("Mirror", "extensions.koharu.Source"))
	}

	@Test
	fun `does not bypass filtering for unrelated sources`() {
		assertFalse(requiresUnfilteredTokenWebView("Example Source", "extensions.example.Source"))
	}
}
