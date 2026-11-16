package com.zsz.studyassistant.ui

internal fun settingsStructureStrings(language: UiLang): Strings {
    val lang = if (language == UiLang.SYSTEM) systemUiLang() else language
    val values = when (lang) {
        UiLang.EN -> listOf("📎 Attachment storage", "Local log management", "Collapse local logs", "View more update history", "Hide older updates", "More", "System notification settings")
        UiLang.JA -> listOf("📎 添付ファイルのストレージ", "端末内のログ管理", "ログ管理を閉じる", "更新履歴をさらに表示", "過去の更新履歴を閉じる", "その他", "システムの通知設定")
        UiLang.KO -> listOf("📎 첨부 파일 저장 공간", "기기 로그 관리", "로그 관리 접기", "업데이트 기록 더 보기", "이전 업데이트 기록 접기", "더 보기", "시스템 알림 설정")
        else -> listOf("📎 附件存储", "本地日志管理", "收起日志管理", "查看更多更新历史", "收起更早更新历史", "更多", "系统通知设置")
    }
    return Strings(listOf("storage", "logs", "hideLogs", "history", "hideHistory", "more", "notifications")
        .zip(values).toMap(), traditional = lang == UiLang.ZH_TW)
}
