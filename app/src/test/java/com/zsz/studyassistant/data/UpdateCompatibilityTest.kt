package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class UpdateCompatibilityTest {
    private val release = UpdateChecker.ReleaseInfo("v0.6.8", "0.6.8", "", "fixture.apk",
        "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.6.8/fixture.apk", null, 7)
    private fun manifest(min: Int = 36) = """{"schema":1,"version":"0.6.8","versionCode":78,"minSdk":$min,"apkName":"fixture.apk","apkSha256":"${"a".repeat(64)}","apkSize":7}"""

    @Test fun android16ReleaseIsBlockedBeforeDownloadOnAndroid15() {
        val parsed = UpdateChecker.withManifest(release, manifest())!!
        assertEquals("update.incompatible", UpdateChecker.compatibilityError(parsed, 35))
        assertNull(UpdateChecker.compatibilityError(parsed, 36))
        assertEquals("a".repeat(64), parsed.assetSha256)
    }
    @Test fun utf8BomAndAdditionalReleaseProvenanceDoNotBlockCompatibleUpdates() {
        val text = "\uFEFF" + manifest().dropLast(1) +
            """, "provenance":{"sourceCommit":"${"b".repeat(40)}","runId":42}}"""
        val parsed = UpdateChecker.withManifest(release, text)
        assertNotNull(parsed)
        assertNull(UpdateChecker.compatibilityError(parsed!!, 36))
    }
    private fun asset(url: String = release.assetUrl.substringBeforeLast('/') + "/update-manifest.json",
                      size: Int = 200) = Json.parseToJsonElement("""
        {"id":42,"name":"update-manifest.json","size":$size,"browser_download_url":"$url"}
    """).jsonObject
    private fun inline(text: String = manifest()) = """
        Visible release notes

        <!-- studyassistant-update-manifest
        $text
        -->
    """.trimIndent()

    @Test fun inlineMetadataAvoidsExtraNetworkAndIsHiddenFromVisibleNotes() {
        val parsed = UpdateChecker.resolveManifest(release.copy(notes = inline()), listOf(asset())) {
            error("Valid inline metadata must not need an asset request")
        }
        assertEquals(36, parsed.minSdk)
        assertEquals(78L, parsed.versionCode)
        assertEquals("Visible release notes", UpdateChecker.visibleReleaseNotes(parsed.notes))
    }
    @Test fun olderReleasesCanUseOfficialApiWhenBrowserAttachmentIsUnavailable() {
        val calls = mutableListOf<UpdateChecker.ManifestEndpoint>()
        val parsed = UpdateChecker.resolveManifest(release, listOf(asset())) { endpoint ->
            calls += endpoint
            if (endpoint.api) UpdateChecker.ManifestFetch.Body(manifest()) else UpdateChecker.ManifestFetch.Unavailable
        }
        assertEquals(36, parsed.minSdk)
        assertEquals(2, calls.size)
        assertFalse(calls.first().api)
        assertEquals("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/assets/42", calls.last().url)
    }
    @Test fun oldIndependentManifestStillWorksWithoutInlineMetadata() {
        var calls = 0
        val parsed = UpdateChecker.resolveManifest(release, listOf(asset())) {
            calls++
            UpdateChecker.ManifestFetch.Body(manifest())
        }
        assertEquals(36, parsed.minSdk)
        assertEquals(1, calls)
    }
    @Test fun unavailableAndInvalidMetadataStayBlockedWithDistinctReasons() {
        val unavailable = UpdateChecker.resolveManifest(release, listOf(asset())) { UpdateChecker.ManifestFetch.Unavailable }
        val invalid = UpdateChecker.resolveManifest(release, listOf(asset())) { UpdateChecker.ManifestFetch.Body("not JSON") }
        assertEquals("update.compatibilityUnavailable", UpdateChecker.compatibilityError(unavailable, 99))
        assertEquals("update.compatibilityInvalid", UpdateChecker.compatibilityError(invalid, 99))
    }
    @Test fun staleInlineMetadataMustNotAuthorizeAnotherApk() {
        val parsed = UpdateChecker.resolveManifest(release.copy(notes = inline(manifest().replace("fixture.apk", "old.apk"))), listOf(asset())) {
            UpdateChecker.ManifestFetch.Unavailable
        }
        assertNull(parsed.minSdk)
        assertEquals("update.compatibilityInvalid", UpdateChecker.compatibilityError(parsed, 99))
    }
    @Test fun invalidOrDuplicateInlineDataCanRecoverOnlyThroughValidIndependentMetadata() {
        for (notes in listOf(inline("{}"), inline() + "\n" + inline())) {
            val parsed = UpdateChecker.resolveManifest(release.copy(notes = notes), listOf(asset())) {
                UpdateChecker.ManifestFetch.Body(manifest())
            }
            assertEquals(36, parsed.minSdk)
        }
    }
    @Test fun invalidAttachmentUrlsAndOversizedMetadataDoNotCauseRequests() {
        for (entry in listOf(asset(url = "https://evil.test/update-manifest.json"), asset(size = 65537))) {
            val parsed = UpdateChecker.resolveManifest(release, listOf(entry)) { error("Invalid metadata must not be fetched") }
            assertEquals("update.compatibilityInvalid", UpdateChecker.compatibilityError(parsed, 99))
        }
        assertNull(UpdateChecker.withManifest(release, manifest() + " ".repeat(65536)))
    }
    @Test fun missingMetadataFailsClosedExceptKnownLegacyVersions() {
        assertEquals("update.compatibilityUnknown", UpdateChecker.compatibilityError(release, 99))
        assertEquals(28, UpdateChecker.legacyMinSdk("0.6.7.8"))
        assertNull(UpdateChecker.legacyMinSdk("0.6.7.9"))
        assertNull(UpdateChecker.legacyMinSdk("garbage"))
        assertNull(UpdateChecker.legacyMinSdk("0.6.7.8_beta"))
        assertEquals("update.incompatible", UpdateChecker.compatibilityError(release.copy(minSdk = 28), 27))
    }
    @Test fun manifestMustBindExactAssetAndVersion() {
        for (text in listOf(manifest().replace("fixture.apk", "other.apk"),
            manifest().replace("0.6.8", "0.6.9"), manifest().replace("\"apkSize\":7", "\"apkSize\":8"),
            manifest().replace("\"schema\":1", "\"schema\":2"), manifest(0), "{}", "broken")) {
            assertNull(text, UpdateChecker.withManifest(release, text))
        }
        assertNull(UpdateChecker.withManifest(release.copy(assetSha256 = "b".repeat(64)), manifest()))
    }
    @Test fun manifestUrlMustBeSameOfficialReleaseAttachment() {
        assertTrue(UpdateChecker.isReleaseAssetUrl(release.assetUrl, release.tagName))
        assertFalse(UpdateChecker.isReleaseAssetUrl(release.assetUrl.replace("github.com", "evil.test"), release.tagName))
        assertFalse(UpdateChecker.isReleaseAssetUrl(release.assetUrl, "v0.6.9"))
        assertFalse(UpdateChecker.isReleaseAssetUrl(release.assetUrl.replace("https://", "http://"), release.tagName))
    }
    @Test fun apkSdkCheckFailsClosedAndDetectsManifestMismatch() {
        assertFalse(UpdateChecker.apkSdkCompatible(null, 36))
        assertFalse(UpdateChecker.apkSdkCompatible(36, 35))
        assertFalse(UpdateChecker.apkSdkCompatible(36, 36, 28))
        assertTrue(UpdateChecker.apkSdkCompatible(36, 36, 36))
        assertTrue(UpdateChecker.apkSdkCompatible(28, 28))
    }
}
