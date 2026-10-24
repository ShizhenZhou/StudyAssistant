package com.zsz.studyassistant.ui

import com.zsz.studyassistant.data.StudyAssistant
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AboutDocumentationTest {
    private val languages = listOf(UiLang.ZH_CN, UiLang.ZH_TW, UiLang.EN, UiLang.JA, UiLang.KO)
    @Test fun configuredModelIsConsistentInReadmeModesAndAbout() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        assertTrue(File(root, "README.md").readText().contains(StudyAssistant.MODEL_TEXT))
        for (language in languages) {
            val strings = stringsFor(language)
            assertTrue(strings["about.features.body"].contains(StudyAssistant.MODEL_TEXT))
            assertTrue(strings["settings.solveModel.desc"].contains(StudyAssistant.MODEL_TEXT))
        }
    }
    @Test fun privacyNamesDestinationsAndAvoidsOldBlanketNoUploadClaim() {
        val obsolete = listOf("也不上传内容", "也不上傳內容", "nothing is uploaded", "内容をアップロードしません", "내용을 업로드하지 않습니다")
        for (language in languages) {
            val privacy = stringsFor(language)["about.privacy.body"]
            for (destination in listOf("DeepSeek", "GitHub", "SAF")) assertTrue(privacy.contains(destination))
            assertFalse(obsolete.any { privacy.contains(it) })
        }
    }
}
