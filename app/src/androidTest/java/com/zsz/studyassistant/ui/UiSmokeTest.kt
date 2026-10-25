package com.zsz.studyassistant.ui

import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose navigation and WebView rendering, in the no-network synthetic package only. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val s get()=stringsFor(UiLang.EN)
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if(node==null) return null
        if(node.text?.toString()==text || node.contentDescription?.toString()==text) return node
        for(i in 0 until node.childCount) find(node.getChild(i),text)?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.uptimeMillis()+8000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow,text)?.let { return it }
            Thread.sleep(80)
        }
        error("Missing UI text: $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo?=waitFor(text)
        while(node!=null && !node.isClickable) node=node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { "Not clickable: $text" }
        instrumentation.waitForIdleSync()
    }
    private fun launch(): ActivityScenario<MainActivity> {
        check(context.packageName=="com.zsz.studyassistant.integration")
        UiLangStore.save(context,UiLang.EN)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val deadline=android.os.SystemClock.uptimeMillis()+8000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(find(root,"Later")!=null) { tap("Later");break }
            if(find(root,"Settings")!=null) break
            Thread.sleep(80)
        }
        return scenario
    }
    @Test fun settingsChildrenReturnToTheirOwnParents() {
        launch().use { scenario ->
            tap("Settings")
            tap(s["settings.aiManage"])
            tap(s["settings.customPrompt"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["settings.aiManage"])
            waitFor(s["settings.aiCropPage"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            tap(s["settings.data"])
            tap(s["data.backup"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["data.crash"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["settings.aiManage"])
        }
    }
    @Test fun englishStatisticsUseEnglishMasteryLabels() {
        launch().use {
            tap("Stats")
            waitFor("Not started");waitFor("Learning");waitFor("Mastered")
        }
    }
    @Test fun renderedNumericListsPreserveNonSequentialSubquestionNumbers() {
        val ready=CountDownLatch(1);val evaluated=CountDownLatch(1)
        var web: WebView?=null;var result: String?=null
        instrumentation.runOnMainSync {
            web=WebView(context).apply {
                settings.javaScriptEnabled=true
                webViewClient=object:WebViewClient() {
                    override fun onPageFinished(view:WebView,url:String) { ready.countDown() }
                }
                loadUrl("file:///android_asset/conversation_render.html")
            }
        }
        try {
            assertTrue("Renderer did not load",ready.await(10,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                web!!.evaluateJavascript("""(function(){const d=document.createElement('div');d.innerHTML=mdToHtml('3. third\n5. fifth');const ol=d.querySelector('ol');let n=ol.start;return Array.from(ol.children).map(li=>{if(li.hasAttribute('value'))n=li.value;return n++;}).join(',');})()""") { result=it;evaluated.countDown() }
            }
            assertTrue(evaluated.await(5,TimeUnit.SECONDS))
            assertEquals("\"3,5\"",result)
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }
}
