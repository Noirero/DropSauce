from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/settings/sources/catalog/SourcesCatalogViewModel.kt"
HELPER = ROOT / ".github/workflows/beta-build-once.yml"
MANUAL_HELPER = """name: Beta Build Once

# Temporary helper kept manual-only after the requested Beta build.
on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  beta:
    name: Build beta APK
    runs-on: ubuntu-latest
    steps:
      - name: Manual helper disabled
        run: echo "Use preview-build.yml for normal manual Beta builds."
"""

OLD = """\tif (selectedLanguage == null) return true
\tif (installedSources.orEmpty().any { extensionLanguageMatches(it.language, selectedLanguage) }) return true
\tif (entry.sources.any { source -> source.lang?.let { extensionLanguageMatches(it, selectedLanguage) } == true }) return true
\treturn entry.lang?.let { extensionLanguageMatches(it, selectedLanguage) } == true
"""

NEW = """\tif (selectedLanguage == null) return true
\tif (!installedSources.isNullOrEmpty()) {
\t\treturn installedSources.any { extensionLanguageMatches(it.language, selectedLanguage) }
\t}
\tval publishedLanguages = entry.sources.mapNotNull { it.lang?.takeIf(String::isNotBlank) }
\tif (publishedLanguages.isNotEmpty()) {
\t\treturn publishedLanguages.any { extensionLanguageMatches(it, selectedLanguage) }
\t}
\treturn entry.lang?.let { extensionLanguageMatches(it, selectedLanguage) } == true
"""


def apply_fix() -> None:
    text = SOURCE.read_text()
    count = text.count(OLD)
    if count != 1:
        raise SystemExit(f"extensionEntryMatchesLanguage target: expected 1 occurrence, found {count}")
    SOURCE.write_text(text.replace(OLD, NEW))


def cleanup() -> None:
    HELPER.write_text(MANUAL_HELPER)
    Path(__file__).unlink()


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "apply"
    if action == "apply":
        apply_fix()
    elif action == "cleanup":
        cleanup()
    else:
        raise SystemExit(f"Unknown action: {action}")
