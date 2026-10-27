package com.zsz.studyassistant.ui

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.MainActivity
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.AppDatabase
import org.junit.Assert.*
import org.junit.Test

class BackupSetupUiIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val s get() = stringsFor(UiLang.EN)
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + 7000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            Thread.sleep(80)
        }
        error("Expected synthetic UI: $text")
    }
    private fun tap(text: String) {
        waitFor(text)
        val deadline = android.os.SystemClock.uptimeMillis() + 6000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(80)
        }
        error("Expected enabled synthetic UI action: $text")
    }
    private fun input(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) input(node.getChild(i))?.let { return it }
        return null
    }
    private fun disabledSwitch(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isCheckable && !node.isEnabled && !node.isChecked) return true
        return (0 until node.childCount).any { disabledSwitch(node.getChild(it)) }
    }
    @Test fun keyComesFirstThenSetupCanBeSkippedWithoutReplayUntilNextLaunch() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("data_upgrade_seen_schema", AppDatabase.SCHEMA_VERSION)
            .putInt("data_upgrade_prompted_schema", AppDatabase.SCHEMA_VERSION).commit()
        UiLangStore.save(context, UiLang.EN)
        ApiKeyStore.saveKey(context, "")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(s["home.key.title"])
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, s["data.autoBackup.setupTitle"]))
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "synthetic-not-a-credential")
                }
                assertTrue(input(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true)
                tap(s["home.key.save"])
                waitFor(s["data.autoBackup.setupTitle"])
                assertTrue(disabledSwitch(instrumentation.uiAutomation.rootInActiveWindow))
                scenario.recreate()
                waitFor(s["data.autoBackup.setupTitle"])
                assertTrue(disabledSwitch(instrumentation.uiAutomation.rootInActiveWindow))
                tap(s["data.autoBackup.setupSkip"])
                scenario.recreate()
                waitFor("Study Assistant")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, s["data.autoBackup.setupTitle"]))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor(s["data.autoBackup.setupTitle"])
                tap(s["data.autoBackup.setupSkip"])
            }
        } finally {
            ApiKeyStore.saveKey(context, "")
            prefs.edit().clear().commit()
        }
    }
}
