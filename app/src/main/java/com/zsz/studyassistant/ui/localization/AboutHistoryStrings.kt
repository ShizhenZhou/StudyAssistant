package com.zsz.studyassistant.ui

internal fun aboutHistoryStrings(language: UiLang): Strings {
    val lang = if (language == UiLang.SYSTEM) systemUiLang() else language
    val values = when (lang) {
        UiLang.EN -> listOf("View past notices", "Details", "Loading notices…", "Could not load notices. Please retry.", "Retry", "No notices for this version.", "Expired", "Fetched online each time this page opens. Removed or disabled notices are excluded from the received list.")
        UiLang.JA -> listOf("過去のお知らせを見る", "詳細情報", "お知らせを読み込み中…", "お知らせを取得できませんでした。再試行してください。", "再試行", "このバージョン向けのお知らせはありません。", "掲載期間終了", "このページを開くたびにオンラインで取得します。取得した一覧から削除・無効化されたお知らせは表示しません。")
        UiLang.KO -> listOf("지난 공지 보기", "상세 정보", "공지를 불러오는 중…", "공지를 불러오지 못했습니다. 다시 시도하세요.", "다시 시도", "이 버전에 해당하는 공지가 없습니다.", "기간 만료", "페이지를 열 때마다 온라인으로 가져옵니다. 받은 목록에서 삭제되거나 비활성화된 공지는 표시하지 않습니다.")
        else -> listOf("查看历史公告", "详细信息", "正在获取公告…", "获取公告失败，请重试。", "重试", "暂无适用于当前版本的公告。", "已过期", "每次打开此页都会联网获取；收到的列表中已移除或停用的公告不再展示。")
    }
    return Strings(listOf("history", "details", "loading", "error", "retry", "empty", "expired", "hint")
        .zip(values).toMap(), traditional = lang == UiLang.ZH_TW)
}
