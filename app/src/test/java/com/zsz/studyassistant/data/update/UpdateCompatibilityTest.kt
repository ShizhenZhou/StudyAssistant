package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class UpdateCompatibilityTest {
    @Test fun compatStableAndBetaRecoverAfterReleaseListFailure() = runBlocking {
        val stableTag = "compat_v0.7.6_Compat"
        val betaTag = "v0.7.7_beta1_Compat"
        val result = fetchCompatFixture { endpoint ->
            when (endpoint) {
                "releases" -> 503 to ""
                "compat-update-hints.json" -> 200 to """{"schema":1,"stableTag":"$stableTag","betaTag":"$betaTag"}"""
                "releases/tags/$stableTag" -> 200 to compatRelease(stableTag, 148, mandatory = true)
                "releases/tags/$betaTag" -> 200 to compatRelease(betaTag, 149, beta = true, mandatory = true)
                else -> error("Unexpected endpoint: $endpoint")
            }
        }!!
        assertEquals(stableTag, result.stable?.tagName)
        assertEquals(148L, result.stable?.versionCode)
        assertTrue(result.stable!!.mandatory)
        assertEquals(betaTag, result.beta?.tagName)
        assertFalse(result.beta!!.mandatory)
        assertFalse(result.historyComplete)
        assertTrue(result.metadataTrusted)
    }

    @Test fun compatReleaseManifestsRecoverAfterRestRateLimit() = runBlocking {
        val stableTag = "compat_v0.7.6_Compat"
        val betaTag = "v0.7.7_beta1_Compat"
        val hints = """{"schema":1,"stableTag":"$stableTag","betaTag":"$betaTag"}"""
        for ((status, headers) in listOf(403 to mapOf("X-RateLimit-Remaining" to "0"), 429 to emptyMap())) {
            val result = fetchCompatFixture(listHeaders = headers) { endpoint ->
                when (endpoint) {
                    "releases" -> status to ""
                    "compat-update-hints.json" -> 200 to hints
                    "download/$stableTag/update-manifest.json" -> 200 to compatManifest(stableTag, 148, mandatory = true)
                        .dropLast(1) + ""","deltas":[{"format":"apk-gdiff1-gzip",
                        "baseSha256":"${"b".repeat(64)}","baseSize":10,"patchName":"fixture.gdiff.gz",
                        "patchSha256":"${"c".repeat(64)}","patchSize":3}]}"""
                    "download/$betaTag/update-manifest.json" -> 200 to compatManifest(betaTag, 149, beta = true)
                    else -> error("Unexpected endpoint after REST rate limit: $endpoint")
                }
            }!!
            assertEquals(stableTag, result.stable?.tagName)
            assertEquals(148L, result.stable?.versionCode)
            assertEquals(
                "https://github.com/ShizhenZhou/StudyAssistant/releases/download/$stableTag/SA-0.7.6_Compat-20261122120000.apk",
                result.stable?.assetUrl
            )
            assertEquals("a".repeat(64), result.stable?.assetSha256)
            assertFalse(result.stable!!.mandatory)
            assertTrue(result.stable!!.deltas.isEmpty())
            assertTrue(result.stable!!.deltaHistory.isEmpty())
            assertEquals(betaTag, result.beta?.tagName)
            assertFalse(result.historyComplete)
            assertFalse(result.metadataTrusted)
        }
    }

    @Test fun rejectedCompatTagDoesNotFallBackToDirectManifest() = runBlocking {
        val tag = "compat_v0.7.6_Compat"
        for (rejected in listOf(404 to "", 422 to "", 200 to "{broken",
            200 to compatRelease(tag, 148, draft = true), 200 to compatRelease("v0.7.6_Compat", 148),
            200 to compatRelease(tag, 148, digestMatches = false))) {
            val result = fetchCompatFixture(UpdateTransport.Config(baseUrl = "https://mirror.example.com")) { endpoint ->
                when (endpoint) {
                    "releases" -> 200 to "[]"
                    "compat-update-hints.json" -> 200 to """{"schema":1,"stableTag":"$tag"}"""
                    "releases/tags/$tag" -> rejected
                    else -> error("Rejected tag should not request $endpoint")
                }
            }
            assertNull(result)
        }
    }

    @Test fun directCompatManifestMustMatchTagChannelAndApkIdentity() = runBlocking {
        val tag = "compat_v0.7.6_Compat"
        val manifest = compatManifest(tag, 148)
        for (invalid in listOf(
            manifest.replace("SA-0.7.6_Compat-", "../SA-0.7.6_Compat-"),
            manifest.replace("SA-0.7.6_Compat-", "SA-0.7.7_Compat-"),
            manifest.replace("SA-0.7.6_Compat-", "StudyAssistant-0.7.6_Compat-"),
            manifest.replace("\"version\":\"0.7.6\"", "\"version\":\"0.7.7\""),
            manifest.replace("\"stable\"", "\"beta\""),
            manifest.replace("\"compat\"", "\"main\""),
            manifest.replace("\"minSdk\":28", "\"minSdk\":27"),
            manifest.replace("\"maxSdk\":35", "\"maxSdk\":36"),
            manifest.replace("\"versionCode\":148", "\"versionCode\":0"),
            manifest.replace("\"apkSize\":7", "\"apkSize\":0"),
            manifest.replace("a".repeat(64), "x".repeat(64))
        )) {
            val result = fetchCompatFixture { endpoint ->
                when (endpoint) {
                    "releases", "releases/tags/$tag" -> 503 to ""
                    "compat-update-hints.json" -> 200 to """{"schema":1,"stableTag":"$tag"}"""
                    "download/$tag/update-manifest.json" -> 200 to invalid
                    else -> error("Unexpected endpoint: $endpoint")
                }
            }
            assertNull(invalid, result)
        }
    }

    @Test fun staleCompatStableHintKeepsPartialHistoryAndIndependentBeta() = runBlocking {
        val listedTag = "v0.7.7_Compat"
        val staleTag = "v0.7.5.10_Compat"
        val betaTag = "v0.7.9_beta1_Compat"
        for (stale in listOf(404 to "", 200 to compatRelease(staleTag, 150, draft = true),
            200 to compatRelease(listedTag, 150), 200 to compatRelease(staleTag, 150, digestMatches = false),
            200 to compatRelease(staleTag, 147))) {
            val listed = compatRelease(listedTag, 149, mandatory = true)
            val firstPage = List(99) { "{}" }.plus(listed).joinToString(",", "[", "]")
            var page = 0
            val result = fetchCompatFixture { endpoint ->
                when (endpoint) {
                    "releases" -> if (++page == 1) 200 to firstPage else 503 to ""
                    "compat-update-hints.json" -> 200 to """{"schema":1,"stableTag":"$staleTag","betaTag":"$betaTag"}"""
                    "releases/tags/$staleTag" -> stale
                    "releases/tags/$betaTag" -> 200 to compatRelease(betaTag, 151, beta = true)
                    else -> error("Unexpected endpoint: $endpoint")
                }
            }!!
            assertEquals(listedTag, result.stable?.tagName)
            assertTrue(result.stable!!.mandatory)
            assertEquals(betaTag, result.beta?.tagName)
            assertFalse(result.historyComplete)
        }
    }

    @Test fun mirroredCompatHintsCannotDisplaceOfficialMandatoryStable() = runBlocking {
        val listedTag = "v0.7.7_Compat"
        val mirroredTag = "v0.7.8_Compat"
        val betaTag = "v0.7.9_beta1_Compat"
        val listed = compatRelease(listedTag, 149, mandatory = true)
        val firstPage = List(99) { "{}" }.plus(listed).joinToString(",", "[", "]")
        var page = 0
        val result = fetchCompatFixture(UpdateTransport.Config(baseUrl = "https://mirror.example.com")) { endpoint ->
            when (endpoint) {
                "releases" -> if (++page == 1) 200 to firstPage else 503 to ""
                "mirror/releases", "releases/tags/$mirroredTag", "releases/tags/$betaTag",
                "download/$mirroredTag/update-manifest.json", "download/$betaTag/update-manifest.json" -> 503 to ""
                "compat-update-hints.json" -> 200 to """{"schema":1,"stableTag":"$mirroredTag","betaTag":"$betaTag"}"""
                "mirror/releases/tags/$mirroredTag" -> 200 to compatRelease(mirroredTag, 150)
                "mirror/releases/tags/$betaTag" -> 200 to compatRelease(betaTag, 151, beta = true)
                else -> error("Unexpected endpoint: $endpoint")
            }
        }!!
        assertEquals(listedTag, result.stable?.tagName)
        assertTrue(result.stable!!.mandatory)
        assertTrue(result.metadataTrusted)
        assertFalse(result.historyComplete)
        assertEquals(betaTag, result.beta?.tagName)
        assertFalse(result.beta!!.mandatory)
    }

    private suspend fun fetchCompatFixture(
        config: UpdateTransport.Config = UpdateTransport.Config(enabled = false),
        listHeaders: Map<String, String> = emptyMap(),
        response: (String) -> Pair<Int, String>
    ): UpdateChecker.AvailableReleases? {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val endpoint = path.substringAfter("/repos/ShizhenZhou/StudyAssistant/")
                .substringAfter("/ShizhenZhou/StudyAssistant/main/config/")
                .substringAfter("/ShizhenZhou/StudyAssistant/releases/")
            val prefix = if (request.url.host == "mirror.example.com") "mirror/" else ""
            val (code, body) = response(prefix + endpoint)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody()).apply {
                    if (endpoint == "releases") listHeaders.forEach { (name, value) -> header(name, value) }
                }.build()
        }.build()
        return UpdateChecker.fetchAvailable(null, "compat", 35, config) { client }
    }

    private fun compatManifest(tag: String, code: Int, beta: Boolean = false, mandatory: Boolean = false): String {
        val version = tag.removePrefix("compat_").removePrefix("v")
        val name = "SA-$version-20261122120000.apk"
        return """{"schema":2,"distribution":"compat","version":"${version.substringBefore('_')}",
            "channel":"${if (beta) "beta" else "stable"}","mandatory":$mandatory,
            "versionCode":$code,"minSdk":28,"maxSdk":35,"apkName":"$name","apkSize":7,"apkSha256":"${"a".repeat(64)}"}"""
    }

    private fun compatRelease(tag: String, code: Int, beta: Boolean = false, mandatory: Boolean = false,
                              draft: Boolean = false, digestMatches: Boolean = true): String {
        val version = tag.removePrefix("compat_").removePrefix("v")
        val name = "SA-$version-20261122120000.apk"
        val publishedDigest = if (digestMatches) "a".repeat(64) else "b".repeat(64)
        val manifest = compatManifest(tag, code, beta, mandatory)
        val notes = JsonPrimitive("<!-- studyassistant-update-manifest\n$manifest\n-->")
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$beta,"body":$notes,"assets":[
            {"name":"$name","size":7,"digest":"sha256:$publishedDigest",
            "browser_download_url":"https://github.com/ShizhenZhou/StudyAssistant/releases/download/$tag/$name"}]}"""
    }

    @Test fun mirrorTransportKeepsCanonicalIdentityAndOfficialFirstOrdering() {
        val canonical = "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.7.3/app.apk"
        val config = UpdateTransport.Config(baseUrl = "https://mirror.example.com/proxy")
        assertEquals(listOf(canonical, "https://mirror.example.com/proxy/$canonical"), UpdateTransport.urls(canonical, config))
        assertEquals(listOf(canonical), UpdateTransport.urls(canonical, config.copy(enabled = false)))
        assertEquals(listOf("https://mirror.example.com/proxy/$canonical", canonical), updateDownloadUrls(canonical, config, true))
        assertEquals(listOf(canonical), updateDownloadUrls(canonical, config.copy(enabled = false), true))
        assertTrue(UpdateTransport.isCanonicalUrl("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases?per_page=100&page=2"))
        assertTrue(UpdateTransport.isCanonicalUrl("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/assets/42"))
        assertTrue(UpdateTransport.isCanonicalUrl("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/tags/compat_v0.7.5"))
        assertTrue(UpdateTransport.isCanonicalUrl("https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/tags/v0.7.7_Compat"))
        assertTrue(UpdateTransport.isCanonicalUrl("https://raw.githubusercontent.com/ShizhenZhou/StudyAssistant/main/config/compat-update-hints.json"))
    }

    @Test fun mirrorCannotReceiveOtherRepositoriesCredentialsOrEncodedPaths() {
        val prefix = "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.7.3/"
        val tagApi = "https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/tags/"
        for (url in listOf(prefix + "%2e%2e/file.apk", prefix + "app.apk?token=secret",
            prefix.replace("ShizhenZhou", "OtherOwner") + "app.apk",
            prefix.replace("github.com", "github.com.evil.test") + "app.apk",
            prefix.replace("https://", "https://user:password@") + "app.apk",
            tagApi + "v0.7.7", tagApi + "v0.7.7_Compat?token=secret", tagApi + "v0.7.7_%43ompat",
            tagApi.replace("ShizhenZhou", "OtherOwner") + "v0.7.7_Compat",
            "https://raw.githubusercontent.com/ShizhenZhou/StudyAssistant/Develop/config/compat-update-hints.json",
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
        assertEquals(0, UpdateChecker.compareVersions("0.7.5.2_Compat", "0.7.5.2"))
        assertTrue(UpdateChecker.isNewer("0.7.5.3_Compat", "0.7.5.2"))
        assertTrue(UpdateChecker.isNewer("0.7.6_Compat", "0.7.6_beta2_Compat"))
        assertTrue(UpdateChecker.isNewer("0.7.6_beta2_Compat", "0.7.6_beta1_Compat"))
        assertEquals("0.7.6_beta2", UpdateChecker.skipIdentity("v0.7.6_beta2_Compat"))
        val text = manifest(28).replace("\"schema\":1", "\"schema\":2").dropLast(1) +
            """, "distribution":"compat", "maxSdk":35, "mandatory":true}"""
        val candidate = release.copy(tagName = "compat_v0.6.8")
        val compat = UpdateChecker.withManifest(candidate, text)!!
        assertNull(UpdateChecker.compatibilityError(compat, 28, "compat"))
        assertNull(UpdateChecker.compatibilityError(compat, 35, "compat"))
        assertEquals("update.distributionIncompatible", UpdateChecker.compatibilityError(compat, 36, "compat"))
        assertEquals("update.distributionIncompatible", UpdateChecker.compatibilityError(compat, 28, "main"))
        assertNull(UpdateChecker.withManifest(candidate, text.replace("\"schema\":2", "\"schema\":1")))
        for (tag in listOf("compat_v0.6.8_Compat", "v0.6.8_Compat", "v0.6.8_beta2_Compat")) {
            val version = tag.removePrefix("compat_").removePrefix("v")
            val beta = "_beta" in version
            val name = "SA-$version-20261122010000.apk"
            val earlierName = "SA-$version-20261121010000.apk"
            val base = "${UpdateChecker.RELEASES_URL}/download/$tag"
            val url = "$base/$name"
            val root = Json.parseToJsonElement("""{"tag_name":"$tag","prerelease":$beta,"assets":[
                {"name":"$earlierName","size":7,"browser_download_url":"$base/$earlierName"},
                {"name":"$name","size":7,"browser_download_url":"$url"}]}""").jsonObject
            val parsed = UpdateChecker.parseRelease(root)!!.first
            assertEquals("compat", parsed.distribution)
            assertEquals(tag, parsed.tagName)
            assertEquals(url, parsed.assetUrl)
            val resolved = UpdateChecker.withManifest(parsed, text.replace("fixture.apk", name))!!
            assertEquals(if (beta) UpdateChecker.Channel.BETA else UpdateChecker.Channel.STABLE, resolved.channel)
            assertNull(UpdateChecker.compatibilityError(resolved, 35, "compat"))
            assertNotNull(UpdateChecker.compatibilityError(resolved, 36, "main"))
            assertNull(UpdateChecker.withManifest(parsed, manifest().replace("fixture.apk", name)))
            assertFalse(UpdateChecker.isReleaseAssetUrl(url, "v0.6.8"))
        }
        assertNull(UpdateChecker.withManifest(release, text))
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
