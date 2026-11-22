package com.zsz.studyassistant.ui

/** Kept with the optional transport settings so they do not expand the main translation table. */
internal fun updateTransportStrings(language: UiLang): Strings {
    val lang = if (language == UiLang.SYSTEM) systemUiLang() else language
    val values = when (lang) {
        UiLang.EN -> listOf(
            "Use a GitHub mirror when needed",
            "Try GitHub first, then the mirror if unavailable. The third party receives your IP and public update URLs, never your API key or study data. APK verification still applies.",
            "Mirror prefix URL", "Save address", "Enter a public HTTPS address without credentials, query parameters or fragments.",
            "Use a mirror that supports GitHub release files and API requests.", "Configure", "Collapse",
            "Switch to mirror", "Switching to mirror…", "Couldn’t switch to the mirror. Try again.")
        UiLang.JA -> listOf(
            "必要な場合に GitHub ミラーへ接続",
            "まず GitHub に接続し、利用できない場合はミラーに切り替えます。第三者には IP と公開更新 URL が送られますが、API キーや学習データは送られません。APK の検証は維持されます。",
            "ミラーの URL プレフィックス", "アドレスを保存", "認証情報・クエリ・フラグメントを含まない公開 HTTPS アドレスを入力してください。",
            "GitHub のリリースファイルと API に対応するミラーを指定してください。", "設定", "閉じる",
            "ミラーに切り替える", "ミラーに切り替え中…", "ミラーに切り替えられませんでした。もう一度お試しください。")
        UiLang.KO -> listOf(
            "필요할 때 GitHub 미러에 연결",
            "GitHub에 먼저 연결하고 사용할 수 없으면 미러로 전환합니다. 제3자에게 IP와 공개 업데이트 URL이 전달되며, API 키나 학습 데이터는 전송하지 않습니다. APK 검증은 유지됩니다.",
            "미러 URL 접두사", "주소 저장", "인증 정보, 쿼리, 프래그먼트가 없는 공개 HTTPS 주소를 입력하세요.",
            "GitHub 릴리스 파일과 API를 지원하는 미러를 지정하세요.", "설정", "접기",
            "미러로 전환", "미러로 전환 중…", "미러로 전환할 수 없습니다. 다시 시도하세요.")
        else -> listOf(
            "必要时连接GitHub镜像站",
            "优先连接 GitHub，连接失败时自动使用镜像。第三方会收到你的 IP 和公开更新地址，不会收到 API Key 或学习数据；安装包仍须通过校验。",
            "镜像前缀地址", "保存地址", "请输入公开 HTTPS 地址，不含账号密码、查询参数或片段。",
            "镜像须支持 GitHub 发布文件及 API 请求。", "设置", "收起",
            "切换到镜像站", "正在切换到镜像站…", "未能切换到镜像站，请重试。")
    }
    return Strings(listOf("title", "disclosure", "address", "save", "invalid", "hint", "configure", "collapse",
        "switch", "switching", "switchFailed").zip(values).toMap(),
        traditional = lang == UiLang.ZH_TW)
}
