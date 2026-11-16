package com.zsz.studyassistant.ui

internal fun crashReportStrings(language: UiLang): Strings {
    val lang = if (language == UiLang.SYSTEM) systemUiLang() else language
    val values = when (lang) {
        UiLang.EN -> listOf(
            "🐞 Feedback & crash report", "The app closed unexpectedly",
            "A crash was recorded locally. You can preview a brief report and decide whether to export it or submit feedback. Nothing is uploaded automatically.",
            "View report", "Later", "Report preview", "No crash recorded; you can still report an issue.",
            "Preparing report…", "Could not read the report. You can still describe the issue on GitHub.",
            "Only the latest crash is included; older logs provide only time and version. Exception messages, study content, keys and file paths are excluded. Review before sharing. GitHub Issues are public; reports are not attached automatically.",
            "Copy report", "Export report", "Open GitHub feedback", "Report copied", "Report exported", "Could not export the report.")
        UiLang.JA -> listOf(
            "🐞 フィードバック・クラッシュ報告", "アプリが予期せず終了しました",
            "クラッシュが端末内に記録されました。簡易レポートを確認し、書き出しや報告を選べます。自動送信はしません。",
            "レポートを確認", "後で", "レポートのプレビュー", "クラッシュ記録はありません。不具合の報告は可能です。",
            "レポートを準備中…", "レポートを読み取れませんでした。GitHub で状況を説明できます。",
            "直近のクラッシュのみを含み、旧ログは日時とバージョンのみです。例外メッセージ、学習内容、キー、ファイルパスは含みません。共有前に確認してください。GitHub の Issue は公開され、レポートは自動添付されません。",
            "レポートをコピー", "レポートを書き出す", "GitHub で報告", "コピーしました", "書き出しました", "書き出せませんでした。")
        UiLang.KO -> listOf(
            "🐞 피드백 및 오류 보고", "앱이 예기치 않게 종료되었습니다",
            "오류가 기기에 기록되었습니다. 간단한 보고서를 확인한 후 내보내거나 피드백을 보낼 수 있습니다. 자동으로 업로드하지 않습니다.",
            "보고서 보기", "나중에", "보고서 미리보기", "기록된 오류가 없습니다. 문제를 직접 보고할 수 있습니다.",
            "보고서 준비 중…", "보고서를 읽지 못했습니다. GitHub에서 상황을 설명할 수 있습니다.",
            "가장 최근 오류만 포함하며 이전 로그는 시간과 버전만 표시합니다. 예외 메시지, 학습 내용, 키 및 파일 경로는 제외됩니다. 공유 전에 확인하세요. GitHub Issue는 공개되며 보고서는 자동 첨부되지 않습니다.",
            "보고서 복사", "보고서 내보내기", "GitHub 피드백 열기", "보고서를 복사했습니다", "보고서를 내보냈습니다", "보고서를 내보내지 못했습니다.")
        else -> listOf(
            "🐞 反馈与崩溃报告", "应用上次意外退出",
            "已在本机记录崩溃信息。你可以先预览精简报告，再决定是否导出或反馈，不会自动上传。",
            "查看报告", "稍后", "报告预览", "暂无崩溃记录，仍可反馈使用中遇到的问题。",
            "正在准备报告…", "无法读取报告，仍可前往 GitHub 描述问题。",
            "仅包含最近一次崩溃，旧日志仅提供时间和版本。排除异常消息正文、学习内容、密钥和文件路径。分享前请检查内容。GitHub Issue 是公开的，报告不会自动附带。",
            "复制报告", "导出报告", "前往 GitHub 反馈", "已复制报告", "已导出报告", "导出报告失败。")
    }
    return Strings(listOf("title", "reminderTitle", "reminderBody", "review", "later", "preview", "empty",
        "loading", "readFailed", "privacy", "copy", "export", "github", "copied", "exported", "exportFailed")
        .zip(values).toMap(), traditional = lang == UiLang.ZH_TW)
}
