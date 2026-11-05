package com.zsz.studyassistant.ui

import android.webkit.WebView
import android.webkit.JavascriptInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Synthetic renderer inputs only; never reads business files or contacts a network. */
class ConversationSecurityIntegrationTest {
    private val bridgeCalls = java.util.concurrent.atomic.AtomicInteger()
    private fun evaluate(script: String, asynchronous: Boolean = false): String? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        val ready = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        var web: WebView? = null
        var result: String? = null
        instrumentation.runOnMainSync {
            web = WebView(context).apply {
                configureConversationWebView(this)
                addJavascriptInterface(object {
                    @JavascriptInterface fun hit() { bridgeCalls.incrementAndGet() }
                    @JavascriptInterface fun done(value: Boolean) { result = value.toString(); evaluated.countDown() }
                }, "SecurityProbe")
                webViewClient = object : ConversationAssetClient() {
                    override fun onPageFinished(view: WebView, url: String) { ready.countDown() }
                }
                loadUrl(CONVERSATION_ASSET_URL)
            }
        }
        try {
            assertTrue("Renderer did not load", ready.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                web!!.evaluateJavascript(script) {
                    if (!asynchronous) { result = it; evaluated.countDown() }
                }
            }
            assertTrue(evaluated.await(10, TimeUnit.SECONDS))
            return result
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }

    @Test fun literalMathPlaceholderCannotInterruptMessageRendering() {
        assertEquals("true", evaluate("""(function(){try{var marker='\uE0000\uE000';var d=document.createElement('div');d.innerHTML=renderMath(marker+' and ${'$'}x+1${'$'}');return d.textContent.indexOf(marker)>=0 && d.querySelectorAll('.katex').length===1;}catch(e){return false;}})()"""))
    }

    @Test fun untrustedStringsStayDataAndFormulaMarkupStillRenders() {
        val attack = "\");SecurityProbe.hit();window.syntheticInjection=true;//"
        val html = "<img src=x onerror=\"SecurityProbe.hit();window.syntheticInjection=true\">"
        val json = buildMessagesJson(listOf(ChatMsg(attack, "$html\u0000\t\u2028\u2029 ${'$'}x+1${'$'}",
            listOf(attack, "x\" onerror=\"SecurityProbe.hit();window.syntheticInjection=true"), thinking = html, thinkingOpen = true)))
        assertEquals("true", evaluate("""(function(){window.syntheticInjection=false;var messages=$json;renderMessages(messages);return !window.syntheticInjection && messages[0].role===${org.json.JSONObject.quote(attack)} && document.querySelectorAll('.msgimg,img[src="x"]').length===0 && document.querySelectorAll('.katex').length===1 && document.body.textContent.indexOf('<img src=x')>=0;})()"""))
        assertEquals(0, bridgeCalls.get())
    }

    @Test fun unicodeMacroExpansionCannotResetTheKatexBudget() {
        // A small finite variant of the upstream GHSA-cvr6-37gx-v8wc regression, never an infinite loop.
        assertEquals("true", evaluate("""(function(){if(katex.version!=='0.16.47')return false;try{katex.renderToString('\\def+{1}x⁺x⁺x⁺x⁺',{maxExpand:3,trust:false});return false;}catch(e){return /Too many expansions/.test(String(e));}})()"""))
    }

    @Test fun rendererCannotRequestPrivateFilesProvidersOrRemoteResources() {
        val client = ConversationAssetClient()
        for (url in listOf("file:///data/user/0/synthetic/private.txt", "content://synthetic/item", "https://example.invalid/asset.js",
            "file:///android_asset/katex/../private.txt")) {
            @Suppress("DEPRECATION") val response = client.shouldInterceptRequest(null, url)
            assertEquals(url, 403, response!!.statusCode)
        }
        @Suppress("DEPRECATION")
        assertNull(client.shouldInterceptRequest(null, "file:///android_asset/katex/katex.min.js"))
        @Suppress("DEPRECATION")
        assertNull(client.shouldInterceptRequest(null, "file:///android_asset/katex/katex.min.css"))
        @Suppress("DEPRECATION")
        assertNull(client.shouldInterceptRequest(null, "file:///android_asset/katex/fonts/KaTeX_Main-Regular.woff2"))
        // Also exercises actual asset loading with all file/provider/network settings disabled.
        assertEquals("true", evaluate("typeof katex.renderToString==='function' && document.styleSheets.length>=2"))
    }

    @Test fun localImageDataAndIntendedBridgeRemainAvailable() {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = try { java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
        } } finally { bitmap.recycle() }
        val image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val json = buildMessagesJson(listOf(ChatMsg("user", "", listOf(image))))
        assertEquals("true", evaluate("""(function(){renderMessages($json);var image=document.querySelector('.msgimg');SecurityProbe.hit();var imageReady=new Promise(function(resolve){if(!image){resolve(false);return;}image.onload=function(){resolve(image.naturalWidth===2);};image.onerror=function(){resolve(false);};if(image.complete)resolve(image.naturalWidth===2);});Promise.all([imageReady,document.fonts.load('16px KaTeX_Main','x')]).then(function(results){SecurityProbe.done(results[0] && results[1].length>0 && results[1].every(function(face){return face.status==='loaded';}) && document.fonts.check('16px KaTeX_Main','x') && image.getAttribute('data-b64')===${org.json.JSONObject.quote(image)});}).catch(function(){SecurityProbe.done(false);});})()""", asynchronous = true))
        assertEquals(1, bridgeCalls.get())
    }
}
