package org.koitharu.kotatsu.search.domain

data class ParsedSearchQuery(
	val text: String,
	val excludes: List<String>,
	val exactPhrase: String?,
	val author: String?,
)

fun sanitizeSearchQuery(raw: String): String {
	val withoutControls = raw.map { char -> if (char.isISOControl()) ' ' else char }.joinToString("")
	return withoutControls.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}

fun parseSearchQuery(raw: String): ParsedSearchQuery {
	val clean = sanitizeSearchQuery(raw)
	val tokens = clean.split(' ')
	val excludes = ArrayList<String>()
	val remaining = ArrayList<String>()
	var author: String? = null

	for (token in tokens) {
		when {
			token.startsWith("author:", ignoreCase = true) -> {
				author = token.substringAfter(':').trim('"').ifBlank { author }
			}
			token.startsWith('-') && token.length > 1 -> excludes += token.substring(1).trim('"')
			else -> remaining += token
		}
	}

	val joined = remaining.joinToString(" ").trim()
	val exactPhrase = joined.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
		?.trim('"')
		?.trim()
		?.ifBlank { null }
	val text = when {
		author != null -> author.orEmpty()
		exactPhrase != null -> exactPhrase
		else -> joined.replace("\"", "").trim()
	}.ifBlank { clean }

	return ParsedSearchQuery(
		text = text,
		excludes = excludes.filter { it.isNotBlank() },
		exactPhrase = exactPhrase,
		author = author,
	)
}
