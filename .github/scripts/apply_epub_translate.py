from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "app/build.gradle",
    "\timplementation libs.play.services.auth\n",
    "\timplementation libs.play.services.auth\n\timplementation 'com.google.mlkit:translate:17.0.3'\n",
)

config = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt"
replace_once(
    config,
    '''                        ToolGridCard(\n                            icon = R.drawable.ic_voice_over,\n                            label = stringResource(R.string.text_to_speech),\n                            onClick = {\n                                dismiss()\n                                callback?.onTextToSpeechClick()\n                            },\n                            modifier = Modifier.weight(1f).height(120.dp),\n                            iconSize = 24.dp,\n                            shape = CircleShape,\n                        )\n''',
    '''                        ToolGridCard(\n                            icon = R.drawable.ic_voice_over,\n                            label = stringResource(R.string.text_to_speech),\n                            onClick = {\n                                dismiss()\n                                callback?.onTextToSpeechClick()\n                            },\n                            modifier = Modifier.weight(1f).height(120.dp),\n                            iconSize = 24.dp,\n                            shape = CircleShape,\n                        )\n                        ToolGridCard(\n                            icon = R.drawable.ic_translate_noirero,\n                            label = stringResource(R.string.epub_translate),\n                            onClick = {\n                                dismiss()\n                                callback?.onTranslateClick()\n                            },\n                            modifier = Modifier.weight(1f).height(120.dp),\n                            iconSize = 24.dp,\n                            shape = CircleShape,\n                        )\n''',
)
replace_once(
    config,
    '''        fun onTextToSpeechClick()\n\n        fun onBookmarkClick()\n''',
    '''        fun onTextToSpeechClick()\n\n        fun onTranslateClick()\n\n        fun onBookmarkClick()\n''',
)

activity = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt"
replace_once(
    activity,
    '''    override fun onTextToSpeechClick() {\n        val reader = readerManager.currentReader as? EpubReaderFragment ?: return\n        settings.isReaderTtsFabVisible = true\n        viewBinding.timerControl.hide()\n        viewBinding.ttsControl.show()\n        if (!tts.isAttached) {\n            reader.startTts()\n        }\n    }\n\n''',
    '''    override fun onTextToSpeechClick() {\n        val reader = readerManager.currentReader as? EpubReaderFragment ?: return\n        settings.isReaderTtsFabVisible = true\n        viewBinding.timerControl.hide()\n        viewBinding.ttsControl.show()\n        if (!tts.isAttached) {\n            reader.startTts()\n        }\n    }\n\n    override fun onTranslateClick() {\n        (readerManager.currentReader as? EpubReaderFragment)?.showTranslationDialog()\n    }\n\n''',
)

epub = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
replace_once(
    epub,
    "import com.google.android.material.textfield.TextInputLayout\n",
    "import com.google.android.material.textfield.TextInputLayout\n"
    "import com.google.mlkit.common.model.DownloadConditions\n"
    "import com.google.mlkit.nl.translate.TranslateLanguage\n"
    "import com.google.mlkit.nl.translate.Translation\n"
    "import com.google.mlkit.nl.translate.Translator\n"
    "import com.google.mlkit.nl.translate.TranslatorOptions\n",
)
replace_once(
    epub,
    '''\tprivate var isTtsPickMode = false\n\tprivate val ttsHighlightSpan = HighlightColorSpan(0)\n''',
    '''\tprivate var isTtsPickMode = false\n\tprivate val ttsHighlightSpan = HighlightColorSpan(0)\n\tprivate val translationOriginals = HashMap<Long, Spanned>()\n\tprivate var activeTranslator: Translator? = null\n\tprivate var translationGeneration = 0\n''',
)

translation_code = '''\tfun showTranslationDialog() {\n\t\tif (chapters.isEmpty()) return\n\t\tval labels = arrayOf(\n\t\t\tgetString(R.string.epub_translate_show_original),\n\t\t\tgetString(R.string.epub_translate_en_id),\n\t\t\tgetString(R.string.epub_translate_ja_id),\n\t\t\tgetString(R.string.epub_translate_ja_en),\n\t\t\tgetString(R.string.epub_translate_ko_id),\n\t\t\tgetString(R.string.epub_translate_ko_en),\n\t\t\tgetString(R.string.epub_translate_zh_id),\n\t\t\tgetString(R.string.epub_translate_zh_en),\n\t\t)\n\t\tMaterialAlertDialogBuilder(requireContext())\n\t\t\t.setTitle(R.string.epub_translate_current_chapter)\n\t\t\t.setMessage(R.string.epub_translate_google_note)\n\t\t\t.setItems(labels) { _, which ->\n\t\t\t\tif (which == 0) {\n\t\t\t\t\trestoreOriginalTranslation()\n\t\t\t\t} else {\n\t\t\t\t\tval pair = TRANSLATION_PAIRS[which - 1]\n\t\t\t\t\ttranslateCurrentChapter(pair.first, pair.second)\n\t\t\t\t}\n\t\t\t}\n\t\t\t.setNegativeButton(android.R.string.cancel, null)\n\t\t\t.show()\n\t}\n\n\tprivate fun translateCurrentChapter(sourceLanguage: String, targetLanguage: String) {\n\t\tval locator = currentLocator()\n\t\tval chapter = chapters.getOrNull(locator.chapter) ?: return\n\t\tval original = translationOriginals[chapter.id] ?: chapter.content ?: return\n\t\ttranslationOriginals.putIfAbsent(chapter.id, SpannedString(original))\n\n\t\tactiveTranslator?.close()\n\t\tval translator = Translation.getClient(\n\t\t\tTranslatorOptions.Builder()\n\t\t\t\t.setSourceLanguage(sourceLanguage)\n\t\t\t\t.setTargetLanguage(targetLanguage)\n\t\t\t\t.build(),\n\t\t)\n\t\tactiveTranslator = translator\n\t\tval generation = ++translationGeneration\n\t\tsetChapterLoading(true)\n\t\tToast.makeText(requireContext(), R.string.epub_translate_preparing, Toast.LENGTH_SHORT).show()\n\t\ttranslator.downloadModelIfNeeded(DownloadConditions.Builder().build())\n\t\t\t.addOnSuccessListener {\n\t\t\t\tif (!isAdded || generation != translationGeneration) return@addOnSuccessListener\n\t\t\t\tval chunks = splitTranslationText(original.toString())\n\t\t\t\ttranslateChunks(translator, chunks, 0, ArrayList(), generation) { translated, error ->\n\t\t\t\t\tif (!isAdded || generation != translationGeneration) return@translateChunks\n\t\t\t\t\tsetChapterLoading(false)\n\t\t\t\t\tif (error != null || translated == null) {\n\t\t\t\t\t\tToast.makeText(requireContext(), R.string.epub_translate_failed, Toast.LENGTH_LONG).show()\n\t\t\t\t\t\treturn@translateChunks\n\t\t\t\t\t}\n\t\t\t\t\tval beforeLength = chapter.text.length.coerceAtLeast(1)\n\t\t\t\t\tchapter.content = SpannedString(translated)\n\t\t\t\t\tval mappedOffset = (translated.length * (locator.offset.toDouble() / beforeLength))\n\t\t\t\t\t\t.toInt().coerceIn(0, translated.length)\n\t\t\t\t\trefreshReader(Locator(locator.chapter, mappedOffset))\n\t\t\t\t\tToast.makeText(requireContext(), R.string.epub_translate_done, Toast.LENGTH_SHORT).show()\n\t\t\t\t}\n\t\t\t}\n\t\t\t.addOnFailureListener {\n\t\t\t\tif (!isAdded || generation != translationGeneration) return@addOnFailureListener\n\t\t\t\tsetChapterLoading(false)\n\t\t\t\tToast.makeText(requireContext(), R.string.epub_translate_model_failed, Toast.LENGTH_LONG).show()\n\t\t\t}\n\t}\n\n\tprivate fun restoreOriginalTranslation() {\n\t\tval locator = currentLocator()\n\t\tval chapter = chapters.getOrNull(locator.chapter) ?: return\n\t\tval original = translationOriginals.remove(chapter.id) ?: run {\n\t\t\tToast.makeText(requireContext(), R.string.epub_translate_already_original, Toast.LENGTH_SHORT).show()\n\t\t\treturn\n\t\t}\n\t\ttranslationGeneration++\n\t\tactiveTranslator?.close()\n\t\tactiveTranslator = null\n\t\tval beforeLength = chapter.text.length.coerceAtLeast(1)\n\t\tchapter.content = original\n\t\tval mappedOffset = (original.length * (locator.offset.toDouble() / beforeLength))\n\t\t\t.toInt().coerceIn(0, original.length)\n\t\trefreshReader(Locator(locator.chapter, mappedOffset))\n\t}\n\n\tprivate fun splitTranslationText(text: String, maxChars: Int = 2500): List<String> {\n\t\tif (text.length <= maxChars) return listOf(text)\n\t\tval result = ArrayList<String>()\n\t\tval current = StringBuilder()\n\t\ttext.split('\\n').forEach { paragraph ->\n\t\t\tif (paragraph.length > maxChars) {\n\t\t\t\tif (current.isNotEmpty()) {\n\t\t\t\t\tresult += current.toString()\n\t\t\t\t\tcurrent.clear()\n\t\t\t\t}\n\t\t\t\tparagraph.chunked(maxChars).forEach(result::add)\n\t\t\t\treturn@forEach\n\t\t\t}\n\t\t\tval extra = paragraph.length + if (current.isEmpty()) 0 else 1\n\t\t\tif (current.length + extra > maxChars && current.isNotEmpty()) {\n\t\t\t\tresult += current.toString()\n\t\t\t\tcurrent.clear()\n\t\t\t}\n\t\t\tif (current.isNotEmpty()) current.append('\\n')\n\t\t\tcurrent.append(paragraph)\n\t\t}\n\t\tif (current.isNotEmpty()) result += current.toString()\n\t\treturn result.ifEmpty { listOf(text) }\n\t}\n\n\tprivate fun translateChunks(\n\t\ttranslator: Translator,\n\t\tchunks: List<String>,\n\t\tindex: Int,\n\t\tresult: MutableList<String>,\n\t\tgeneration: Int,\n\t\tonComplete: (String?, Throwable?) -> Unit,\n\t) {\n\t\tif (generation != translationGeneration) return\n\t\tif (index >= chunks.size) {\n\t\t\tonComplete(result.joinToString("\\n"), null)\n\t\t\treturn\n\t\t}\n\t\ttranslator.translate(chunks[index])\n\t\t\t.addOnSuccessListener { translated ->\n\t\t\t\tif (generation != translationGeneration) return@addOnSuccessListener\n\t\t\t\tresult += translated\n\t\t\t\ttranslateChunks(translator, chunks, index + 1, result, generation, onComplete)\n\t\t\t}\n\t\t\t.addOnFailureListener { error ->\n\t\t\t\tif (generation == translationGeneration) onComplete(null, error)\n\t\t\t}\n\t}\n\n'''
replace_once(epub, "\toverride fun onDestroyView() {\n", translation_code + "\toverride fun onDestroyView() {\n")
replace_once(
    epub,
    '''\t\tcolorAnimator?.cancel()\n\t\tcolorAnimator = null\n''',
    '''\t\tcolorAnimator?.cancel()\n\t\tcolorAnimator = null\n\t\ttranslationGeneration++\n\t\tactiveTranslator?.close()\n\t\tactiveTranslator = null\n\t\ttranslationOriginals.clear()\n''',
)
replace_once(
    epub,
    '''\tcompanion object {\n\t\tprivate const val EPUB_MODE_SCROLL = "scroll"\n''',
    '''\tcompanion object {\n\t\tprivate val TRANSLATION_PAIRS = listOf(\n\t\t\tTranslateLanguage.ENGLISH to TranslateLanguage.INDONESIAN,\n\t\t\tTranslateLanguage.JAPANESE to TranslateLanguage.INDONESIAN,\n\t\t\tTranslateLanguage.JAPANESE to TranslateLanguage.ENGLISH,\n\t\t\tTranslateLanguage.KOREAN to TranslateLanguage.INDONESIAN,\n\t\t\tTranslateLanguage.KOREAN to TranslateLanguage.ENGLISH,\n\t\t\tTranslateLanguage.CHINESE to TranslateLanguage.INDONESIAN,\n\t\t\tTranslateLanguage.CHINESE to TranslateLanguage.ENGLISH,\n\t\t)\n\n\t\tprivate const val EPUB_MODE_SCROLL = "scroll"\n''',
)

Path("app/src/main/res/values/strings_noirero_translate.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="epub_translate">Translate</string>\n    <string name="epub_translate_current_chapter">Translate current chapter</string>\n    <string name="epub_translate_show_original">Original / Restore original</string>\n    <string name="epub_translate_en_id">English → Indonesia</string>\n    <string name="epub_translate_ja_id">Japanese → Indonesia</string>\n    <string name="epub_translate_ja_en">Japanese → English</string>\n    <string name="epub_translate_ko_id">Korean → Indonesia</string>\n    <string name="epub_translate_ko_en">Korean → English</string>\n    <string name="epub_translate_zh_id">Chinese → Indonesia</string>\n    <string name="epub_translate_zh_en">Chinese → English</string>\n    <string name="epub_translate_google_note">On-device translation powered by Google ML Kit. The language model may be downloaded the first time.</string>\n    <string name="epub_translate_preparing">Preparing translation model…</string>\n    <string name="epub_translate_done">Chapter translated</string>\n    <string name="epub_translate_failed">Could not translate this chapter.</string>\n    <string name="epub_translate_model_failed">Could not download the translation model.</string>\n    <string name="epub_translate_already_original">This chapter is already showing the original text.</string>\n</resources>\n''')

Path("app/src/main/res/drawable/ic_translate_noirero.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="24"\n    android:viewportHeight="24">\n    <path\n        android:fillColor="@android:color/white"\n        android:pathData="M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8L4.69,8c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z" />\n</vector>\n''')
