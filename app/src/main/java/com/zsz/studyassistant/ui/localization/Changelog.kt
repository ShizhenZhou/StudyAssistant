package com.zsz.studyassistant.ui

import java.io.InputStream
import com.zsz.studyassistant.data.UpdateChecker

internal data class ChangelogSections(val current: String = "", val older: String = "")

/** Keep the installed release family visible; a beta also keeps the preceding stable family. */
internal fun changelogSections(text: String, installedVersion: String): ChangelogSections {
    val versionPattern = "\\d+(?:\\.\\d+)+(?:_beta\\d*)?"
    val headings = Regex("(?m)^v($versionPattern)(?:→+v($versionPattern))?(?=\\s|[（(]|$)").findAll(text).toList()
    if (headings.isEmpty()) return ChangelogSections(older = text)
    val blocks = headings.mapIndexed { index, heading ->
        // A consolidated history range belongs to its final release family.
        heading.groupValues[2].ifEmpty { heading.groupValues[1] } to text.substring(heading.range.first,
            headings.getOrNull(index + 1)?.range?.first ?: text.length).trim()
    }
    val installed = installedVersion.removePrefix("v")
    val beta = installed.contains("_beta")
    val stable = if (beta) blocks.map { it.first }
        .filter { !it.contains("_beta") && UpdateChecker.compareVersions(it, installed) <= 0 }
        .maxWithOrNull { a, b -> UpdateChecker.compareVersions(a, b) } else installed
    fun family(version: String) = UpdateChecker.normalizeVersion(version).split('.').take(3)
    val (current, older) = blocks.partition { (version, _) ->
        if (version.contains("_beta")) beta &&
            UpdateChecker.normalizeVersion(version) == UpdateChecker.normalizeVersion(installed) &&
            UpdateChecker.compareVersions(version, installed) <= 0
        else stable != null && family(version) == family(stable) &&
            UpdateChecker.compareVersions(version, stable) <= 0
    }
    return ChangelogSections(current.joinToString("\n\n") { it.second }, older.joinToString("\n\n") { it.second })
}

internal fun changelogText(lang: UiLang, openAsset: (String) -> InputStream): String {
    val file = when (lang) {
        UiLang.EN -> "en"
        UiLang.JA -> "ja"
        UiLang.KO -> "ko"
        UiLang.SYSTEM, UiLang.ZH_CN, UiLang.ZH_TW -> "zh"
    }
    val text = openAsset("changelog/$file.txt").bufferedReader(Charsets.UTF_8).use {
        it.readText().removeSuffix("\n")
    }
    return if (lang == UiLang.ZH_TW) s2t(text) else text
}
