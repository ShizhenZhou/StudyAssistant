package com.zsz.studyassistant.ui

internal fun aboutHistoryStrings(language: UiLang): Strings {
    val lang = if (language == UiLang.SYSTEM) systemUiLang() else language
    val values = when (lang) {
        UiLang.EN -> listOf(
            "View past notices", "Details", "Loading notices…", "Could not load notices. Please retry.", "Retry",
            "No notices for this version.", "Expired",
            "Fetched online when this page opens or you refresh. Removed or disabled notices are excluded from the received list.",
            "Refresh", "Published: {time} (Beijing time, UTC+8)", "Notice details", "Hide notice details", "Notice ID: {id}"
        )
        UiLang.JA -> listOf(
            "過去のお知らせを見る", "詳細情報", "お知らせを読み込み中…", "お知らせを取得できませんでした。再試行してください。", "再試行",
            "このバージョン向けのお知らせはありません。", "掲載期間終了",
            "このページを開くときや更新時にオンラインで取得します。取得した一覧から削除・無効化されたお知らせは表示しません。",
            "更新", "公開日時：{time}（北京時間、UTC+8）", "お知らせの詳細", "詳細を閉じる", "お知らせID：{id}"
        )
        UiLang.KO -> listOf(
            "지난 공지 보기", "상세 정보", "공지를 불러오는 중…", "공지를 불러오지 못했습니다. 다시 시도하세요.", "다시 시도",
            "이 버전에 해당하는 공지가 없습니다.", "기간 만료",
            "페이지를 열거나 새로고침할 때 온라인으로 가져옵니다. 받은 목록에서 삭제되거나 비활성화된 공지는 표시하지 않습니다.",
            "새로고침", "게시 일시: {time} (베이징 시간, UTC+8)", "공지 상세 정보", "상세 정보 접기", "공지 ID: {id}"
        )
        else -> listOf(
            "查看历史公告", "详细信息", "正在获取公告…", "获取公告失败，请重试。", "重试", "暂无适用于当前版本的公告。", "已过期",
            "每次打开或刷新此页都会联网获取；收到的列表中已移除或停用的公告不再展示。",
            "刷新", "发布时间：{time}（北京时间，UTC+8）", "公告详情", "收起公告详情", "公告编号：{id}"
        )
    }
    return Strings(listOf("history", "details", "loading", "error", "retry", "empty", "expired", "hint",
        "refresh", "published", "noticeDetails", "hideNoticeDetails", "noticeId")
        .zip(values).toMap(), traditional = lang == UiLang.ZH_TW)
}
