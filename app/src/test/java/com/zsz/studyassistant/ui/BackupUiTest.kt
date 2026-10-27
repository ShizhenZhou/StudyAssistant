package com.zsz.studyassistant.ui

import org.junit.Assert.*
import org.junit.Test

class BackupUiTest {
    @Test fun `folder path keeps logical volume and all nested folders`() {
        assertEquals("Internal storage/Documents/Study/Backups", backupFolderLabel("primary:Documents/Study/Backups", "Backups", "Internal storage"))
        assertEquals("1234-ABCD:/Study", backupFolderLabel("1234-ABCD:Study", "Study", "Internal storage"))
        assertEquals("Cloud backups", backupFolderLabel("opaque-provider-id", "Cloud backups", "Internal storage"))
    }
    @Test fun `internal storage name follows all supported UI languages`() {
        val labels = mapOf(UiLang.ZH_CN to "内部存储", UiLang.EN to "Internal storage",
            UiLang.JA to "内部ストレージ", UiLang.KO to "내부 저장소")
        for ((language, label) in labels) {
            val translated = stringsFor(language)["data.autoBackup.internalStorage"]
            assertEquals(label, translated)
            assertEquals("$label/Documents/Study/Backups",
                backupFolderLabel("primary:Documents/Study/Backups", "Backups", translated))
            assertEquals("$label/", backupFolderLabel("primary:", null, translated))
        }
    }
    @Test fun `short paths stay intact and long paths preserve the end`() {
        assertEquals("primary:/Backups", shortenBackupPath("primary:/Backups") { it.length <= 30 })
        val short = shortenBackupPath("primary:/Documents/Study/Backups") { it.length <= 15 }
        assertTrue(short.startsWith(".../"))
        assertTrue(short.endsWith("Backups"))
        assertTrue(short.length <= 15)
        assertEquals("", shortenBackupPath("primary:/Backups") { false })
    }
}
