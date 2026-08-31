from pathlib import Path

p = Path('app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalMangaRepository.kt')
s = p.read_text()
start = s.index('\tprivate suspend fun getAllFiles() =')
end = s.index('\n\tprivate fun Collection<LocalManga>.unwrap()', start)
new = '''\tprivate suspend fun getAllFiles() = storageManager.getReadableDirs()
\t\t.asSequence()
\t\t.flatMap { dir ->
\t\t\tdir.withChildren { children ->
\t\t\t\tval result = ArrayList<File>()
\t\t\t\tchildren.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
\t\t\t\t\tif (child.isDirectory && child.name == LocalMangaOutput.DOWNLOADS_DIR_NAME) {
\t\t\t\t\t\tscanDownloadRoot(child, result)
\t\t\t\t\t} else {
\t\t\t\t\t\tscanLegacyEntry(child, result)
\t\t\t\t\t}
\t\t\t\t}
\t\t\t\tresult
\t\t\t}
\t\t}

\tprivate fun scanDownloadRoot(downloads: File, result: MutableList<File>) {
\t\tdownloads.withChildren { children ->
\t\t\tchildren.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
\t\t\t\twhen {
\t\t\t\t\tchild.isDirectory && child.name == LocalMangaOutput.NOVEL_DIR_NAME -> scanNovelRoot(child, result)
\t\t\t\t\tchild.isDirectory && File(child, LocalMangaOutput.SOURCE_DIR_MARKER).isFile -> child.withChildren { mangaDirs ->
\t\t\t\t\t\tmangaDirs.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
\t\t\t\t\t}
\t\t\t\t\telse -> result.add(child)
\t\t\t\t}
\t\t\t}
\t\t}
\t}

\tprivate fun scanNovelRoot(novelRoot: File, result: MutableList<File>) {
\t\tnovelRoot.withChildren { novelSources ->
\t\t\tnovelSources.filterNot { it.isHidden || it.shouldSkip() }.forEach { sourceDir ->
\t\t\t\tif (sourceDir.isDirectory) sourceDir.withChildren { novels ->
\t\t\t\t\tnovels.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
\t\t\t\t}
\t\t\t}
\t\t}
\t}

\tprivate fun scanLegacyEntry(child: File, result: MutableList<File>) {
\t\twhen {
\t\t\tchild.isDirectory && child.name == LocalMangaOutput.NOVEL_DIR_NAME -> scanNovelRoot(child, result)
\t\t\tchild.isDirectory && File(child, LocalMangaOutput.SOURCE_DIR_MARKER).isFile -> child.withChildren { sourceChildren ->
\t\t\t\tsourceChildren.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
\t\t\t}
\t\t\telse -> result.add(child)
\t\t}
\t}
'''
p.write_text(s[:start] + new + s[end:])
