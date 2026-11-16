package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class UpdateCompatibilityTest {
    @Test fun mirrorTransportKeepsCanonicalIdentityAndOfficialFirstOrdering() {
        val canonical = "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.7.3/app.apk"
        val config = UpdateTransport.Config(baseUrl = "https://mirror.example.com/proxy")
        assertEquals(listOf(canonical, "https://mirror.example.com/proxy/$canonical"), UpdateTransport.urls(canonical, config))
        assertEquals(listOf(canonical), UpdateTransport.urls(canonical, config.copy(enabled = false)))
        assertTrue(UpdateTransport.isCanonicalUrl("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases?per_page=100&page=2"))
        assertTrue(UpdateTransport.isCanonicalUrl("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/assets/42"))
    }

    @Test fun mirrorCannotReceiveOtherRepositoriesCredentialsOrEncodedPaths() {
        val prefix = "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.7.3/"
        for (url in listOf(prefix + "%2e%2e/file.apk", prefix + "app.apk?token=secret",
            prefix.replace("ShizhenZhou", "OtherOwner") + "app.apk",
            prefix.replace("github.com", "github.com.evil.test") + "app.apk",
            prefix.replace("https://", "https://user:password@") + "app.apk",
            "https://api.github.com/user", "https://raw.githubusercontent.com/Other/Repo/main/key")) {
            assertFalse(url, UpdateTransport.isCanonicalUrl(url))
        }
    }

    @Test fun mirrorSettingsRejectUnsafeAddressesWithoutResolvingOrSendingAnything() {
        for (address in listOf("http://mirror.example.com", "https://user:password@mirror.example.com",
            "https://mirror.example.com/?key=secret", "https://mirror.example.com/#fragment",
            "https://localhost", "https://host.local", "https://127.0.0.1", "https://10.0.0.1",
            "https://[::1]", "https://2130706433", "https://mirror.example.com/../proxy",
            "https://mirror.example.com/%2e%2e/proxy")) {
            assertNull(address, UpdateTransport.normalizedBaseUrl(address))
        }
        assertEquals("https://mirror.example.com/path/", UpdateTransport.normalizedBaseUrl(" https://mirror.example.com/path "))
    }

    @Test fun malformedManifestCommentsStayHiddenWithoutRelaxingCompatibility() {
        val marker = "<!-- studyassistant-update-manifest"
        for (hidden in listOf("$marker {broken} -->", "$marker\r\n{broken}\r\n-->",
            "<!--studyassistant-update-manifest {broken}-->", "<!--  studyassistant-update-manifest\n{broken}")) {
            assertEquals("Visible notes", UpdateChecker.visibleReleaseNotes("Visible notes\n$hidden"))
            assertEquals("Visible notes", UpdateChecker.prettyReleaseNotes("Visible notes\n$hidden"))
        }
        assertEquals("Before\n\nAfter", UpdateChecker.visibleReleaseNotes("Before\n$marker {} -->\nAfter<!-- private -->"))
        assertEquals("Math: x < y and y > z", UpdateChecker.visibleReleaseNotes("Math: x < y and y > z"))
        val invalid = release.copy(notes = "Visible notes\n$marker\n{broken}")
        val resolved = UpdateChecker.resolveManifest(invalid, emptyList()) { error("no attachment to fetch") }
        assertEquals("update.compatibilityInvalid", resolved.compatibilityIssue)
    }
    private val release = UpdateChecker.ReleaseInfo("v0.6.8", "0.6.8", "", "fixture.apk",
        "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.6.8/fixture.apk", null, 7)
    private fun manifest(min: Int = 36) = """{"schema":1,"version":"0.6.8","versionCode":78,"minSdk":$min,"apkName":"fixture.apk","apkSha256":"${"a".repeat(64)}","apkSize":7}"""

    @Test fun compatibilityDistributionSeparatesMandatoryTargetsAndRejectsAmbiguousSchema() {
        val text = manifest(28).replace("\"schema\":1", "\"schema\":2").dropLast(1) +
            """, "distribution":"compat", "maxSdk":35, "mandatory":true}"""
        val candidate = release.copy(tagName = "compat_v0.6.8")
        val compat = UpdateChecker.withManifest(candidate, text)!!
        assertNull(UpdateChecker.compatibilityError(compat, 28, "compat"))
        assertNull(UpdateChecker.compatibilityError(compat, 35, "compat"))
        assertEquals("update.distributionIncompatible", UpdateChecker.compatibilityError(compat, 36, "compat"))
        assertEquals("update.distributionIncompatible", UpdateChecker.compatibilityError(compat, 28, "main"))
        assertNull(UpdateChecker.withManifest(candidate, text.replace("\"schema\":2", "\"schema\":1")))
        val main = release.copy(minSdk = 36, versionCode = 999, mandatory = true)
        val optional = compat.copy(mandatory = false)
        assertEquals(optional, UpdateChecker.stableUpdate(listOf(main, optional), 28, "compat") { true })
        assertEquals(main, UpdateChecker.stableUpdate(listOf(compat, main), 36, "main") { true })
    }

    @Test fun mainSelectionSkipsCompatWithHigherCodeInBothChannels() {
        val main = release.copy(minSdk = 36, versionCode = 100)
        val beta = main.copy(channel = UpdateChecker.Channel.BETA, versionCode = 101)
        val compat = main.copy(distribution = "compat", minSdk = 28, maxSdk = 35, versionCode = 999)
        val candidates = listOf(compat, compat.copy(channel = UpdateChecker.Channel.BETA), main, beta)
        assertEquals(UpdateChecker.AvailableReleases(main, beta),
            UpdateChecker.resolveAvailable(candidates.map { it to emptyList() }, "main") { it.first })
    }

    @Test fun compatTagRetainsVersionForNewClientsAndZeroForLegacyClients() {
        val tag = "compat_v0.99.99.99"
        assertEquals("0.99.99.99", UpdateChecker.normalizeVersion(tag))
        // Frozen v0.7.4.1 normalization, before distribution support was added.
        val legacyVersion = tag.trim().removePrefix("v").removePrefix("V").substringBefore('_').trim()
        assertEquals("compat", legacyVersion)
        assertFalse(UpdateChecker.isNewer(legacyVersion, "0.7.4.1"))
        assertEquals("0.99.99.99", release.copy(tagName = tag).displayVersion)
        val assetName = "SACompat-0.99.99.99-20261116160000.apk"
        val root = Json.parseToJsonElement("""{"tag_name":"$tag","assets":[
            {"name":"SACompat-0.99.99.99-20261116150000.apk","size":7,"browser_download_url":"https://github.com/ShizhenZhou/StudyAssistant/releases/download/$tag/SACompat-0.99.99.99-20261116150000.apk"},
            {"name":"$assetName","size":7,"browser_download_url":"https://github.com/ShizhenZhou/StudyAssistant/releases/download/$tag/$assetName"}
        ]}""").jsonObject
        val parsed = UpdateChecker.parseRelease(root)!!.first
        assertEquals(assetName, parsed.assetName)
        assertEquals("compat", parsed.distribution)
    }

    @Test fun mandatoryNeedsAnExplicitBooleanAndStableChannel() {
        assertFalse(UpdateChecker.withManifest(release, manifest())!!.mandatory)
        val required = manifest().dropLast(1) + """, "mandatory":true}"""
        assertTrue(UpdateChecker.withManifest(release, required)!!.mandatory)
        assertFalse(UpdateChecker.withManifest(release.copy(channel = UpdateChecker.Channel.BETA), required)!!.mandatory)
        assertFalse(UpdateChecker.withManifest(release,
            required.dropLast(1) + """, "channel":"beta"}""")!!.mandatory)
        for (invalid in listOf("\"true\"", "1", "null", "{}")) {
            assertNull(UpdateChecker.withManifest(release, manifest().dropLast(1) + ",\"mandatory\":$invalid}"))
        }
    }

    @Test fun missedMandatoryStableRequiresLatestInstallableStableOnly() {
        val old = release.copy(tagName = "v0.7.1", version = "0.7.1", versionCode = 112, minSdk = 36, mandatory = true)
        val latest = old.copy(tagName = "v0.7.3", version = "0.7.3", versionCode = 114, mandatory = false)
        val future = latest.copy(tagName = "v0.8.0", versionCode = 120, minSdk = 37)
        val preview = future.copy(minSdk = 36, channel = UpdateChecker.Channel.BETA, mandatory = true)
        val history = listOf(preview, future, latest, old)
        assertEquals(latest.copy(mandatory = true), UpdateChecker.stableUpdate(history, 36, "main") { it.versionCode!! > 111 })
        assertEquals(latest, UpdateChecker.stableUpdate(history, 36, "main") { it.versionCode!! > 112 })
        assertNull(UpdateChecker.stableUpdate(history, 35, "main") { true })
        assertNull(UpdateChecker.stableUpdate(history, 36, "main") { false })
    }

    @Test fun malformedDeltaFallsBackToFullApkWithoutDroppingCompatibility() {
        val base = manifest().dropLast(1)
        val valid = """{"format":"gdiff4-gzip","baseSha256":"${"b".repeat(64)}","baseSize":10,"patchName":"fixture.gdiff.gz","patchSha256":"${"c".repeat(64)}","patchSize":3}"""
        val parsed = UpdateChecker.withManifest(release, "$base,\"deltas\":[$valid]}")!!
        assertEquals(1, parsed.deltas.size)
        assertTrue(parsed.deltas.single().patchUrl.startsWith(release.assetUrl.substringBeforeLast('/') + "/"))
        val archive = valid.replace("gdiff4-gzip", "apk-gdiff1-gzip").replace("fixture.gdiff.gz", "fixture-zip.gdiff.gz")
        val both = UpdateChecker.withManifest(release, "$base,\"deltas\":[$valid,$archive,$archive]}")!!
        assertEquals(listOf("gdiff4-gzip", "apk-gdiff1-gzip"), both.deltas.map { it.format })
        for (bad in listOf(valid.replace("fixture.gdiff.gz", "../fixture.gdiff.gz"),
            valid.replace("\"patchSize\":3", "\"patchSize\":7"), valid.replace("gdiff4-gzip", "script"))) {
            val full = UpdateChecker.withManifest(release, "$base,\"deltas\":[$bad]}")!!
            assertTrue(full.deltas.isEmpty())
            assertEquals(36, full.minSdk)
        }
    }

    @Test fun unrecognizedExplicitReleaseChannelCannotBeAcceptedAsStable() {
        val text = manifest().dropLast(1) + """, "channel":"canary"}"""
        assertNull(UpdateChecker.withManifest(release, text))
    }
    @Test fun explicitBetaManifestRemainsOptionalEvenIfGitHubFlagWasMissing() {
        val parsed = UpdateChecker.withManifest(release, manifest().dropLast(1) + """, "channel":"beta"}""")!!
        assertEquals(UpdateChecker.Channel.BETA, parsed.channel)
        assertEquals(UpdateChecker.Channel.STABLE, UpdateChecker.withManifest(release, manifest())!!.channel)
    }
    @Test fun stableMetadataCannotDowngradeAnExplicitPrereleaseToStable() {
        val parsed = UpdateChecker.withManifest(release.copy(channel=UpdateChecker.Channel.BETA),
            manifest().dropLast(1) + """, "channel":"stable"}""")!!
        assertEquals(UpdateChecker.Channel.BETA, parsed.channel)
    }
    @Test fun optionalBetaIsListedAboveAnIndependentStableUpdate() {
        val stable=release.copy(version="0.6.9",versionCode=90)
        val beta=release.copy(version="0.7.0",versionCode=91,channel=UpdateChecker.Channel.BETA)
        val result=UpdateChecker.AvailableReleases(stable,beta)
        assertEquals(listOf(beta,stable),result.updates { true })
        assertEquals(listOf(beta),result.updates { it==beta })
        val olderBeta=beta.copy(versionCode=89)
        assertEquals(listOf(olderBeta,stable),UpdateChecker.AvailableReleases(stable,olderBeta).updates { true })
        assertEquals(listOf(stable),UpdateChecker.AvailableReleases(stable,olderBeta).updates { (it.versionCode ?: 0)>89 })
        assertTrue(result.updates { false }.isEmpty())
    }
    @Test fun explicitGitHubPrereleasesAndDraftsAreDistinguished() {
        val root=Json.parseToJsonElement("""{"tag_name":"v0.6.8","prerelease":true,"draft":false,"body":"notes",
            "assets":[{"name":"fixture.apk","size":7,"browser_download_url":"${release.assetUrl}"}]}""").jsonObject
        assertEquals(UpdateChecker.Channel.BETA,UpdateChecker.parseRelease(root)!!.first.channel)
        assertNull(UpdateChecker.parseRelease(kotlinx.serialization.json.JsonObject(root + ("draft" to kotlinx.serialization.json.JsonPrimitive(true)))))
    }
    @Test fun attachmentOnlyBetaDoesNotHideTheNextRealStableRelease() {
        val hiddenBeta=release.copy(tagName="v0.7.0",version="0.7.0",channel=UpdateChecker.Channel.STABLE)
        val stable=release.copy(tagName="v0.6.9",version="0.6.9",versionCode=90)
        val explicitBeta=release.copy(tagName="v0.7.1",version="0.7.1",versionCode=92,channel=UpdateChecker.Channel.BETA)
        val calls=mutableListOf<String>()
        val result=UpdateChecker.resolveAvailable(listOf(hiddenBeta,stable,explicitBeta).map { it to emptyList() }, distribution = "main") {
            calls+=it.first.version
            if (it.first==hiddenBeta) hiddenBeta.copy(channel=UpdateChecker.Channel.BETA,versionCode=91) else it.first
        }
        assertEquals(stable,result.stable);assertEquals(explicitBeta,result.beta)
        assertEquals(listOf("0.7.0","0.6.9","0.7.1"),calls)
        assertEquals(listOf(explicitBeta,stable),result.updates { true })
    }
    @Test fun android16ReleaseIsBlockedBeforeDownloadOnAndroid15() {
        val parsed = UpdateChecker.withManifest(release, manifest())!!
        assertEquals("update.incompatible", UpdateChecker.compatibilityError(parsed, 35, "main"))
        assertNull(UpdateChecker.compatibilityError(parsed, 36, "main"))
        assertEquals("a".repeat(64), parsed.assetSha256)
    }
    @Test fun utf8BomAndAdditionalReleaseProvenanceDoNotBlockCompatibleUpdates() {
        val text = "\uFEFF" + manifest().dropLast(1) +
            """, "provenance":{"sourceCommit":"${"b".repeat(40)}","runId":42}}"""
        val parsed = UpdateChecker.withManifest(release, text)
        assertNotNull(parsed)
        assertNull(UpdateChecker.compatibilityError(parsed!!, 36, "main"))
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
        assertEquals("update.compatibilityUnavailable", UpdateChecker.compatibilityError(unavailable, 99, "main"))
        assertEquals("update.compatibilityInvalid", UpdateChecker.compatibilityError(invalid, 99, "main"))
    }
    @Test fun staleInlineMetadataMustNotAuthorizeAnotherApk() {
        val parsed = UpdateChecker.resolveManifest(release.copy(notes = inline(manifest().replace("fixture.apk", "old.apk"))), listOf(asset())) {
            UpdateChecker.ManifestFetch.Unavailable
        }
        assertNull(parsed.minSdk)
        assertEquals("update.compatibilityInvalid", UpdateChecker.compatibilityError(parsed, 99, "main"))
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
            assertEquals("update.compatibilityInvalid", UpdateChecker.compatibilityError(parsed, 99, "main"))
        }
        assertNull(UpdateChecker.withManifest(release, manifest() + " ".repeat(65536)))
    }
    @Test fun missingMetadataFailsClosedExceptKnownLegacyVersions() {
        assertEquals("update.compatibilityUnknown", UpdateChecker.compatibilityError(release, 99, "main"))
        assertEquals(28, UpdateChecker.legacyMinSdk("0.6.7.8"))
        assertNull(UpdateChecker.legacyMinSdk("0.6.7.9"))
        assertNull(UpdateChecker.legacyMinSdk("garbage"))
        assertNull(UpdateChecker.legacyMinSdk("0.6.7.8_beta"))
        assertEquals("update.incompatible", UpdateChecker.compatibilityError(release.copy(minSdk = 28), 27, "main"))
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
