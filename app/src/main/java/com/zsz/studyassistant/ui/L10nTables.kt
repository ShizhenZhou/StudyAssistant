package com.zsz.studyassistant.ui

/**
 * 界面文案表（简体中文为基准，其余语言按 key 对应）。
 *
 * 支持的应用语言：跟随系统 / 简体中文 / 繁體中文 / English / 日本語 / 한국어。
 * 繁體中文不单独写表，由简体经 s2t() 转换（见 L10n.kt）。
 *
 * 组织方式：每批新增一个分区（B0_xx、B1_xx …），文件末尾把各分区**合并**成
 * ZH / EN / JA / KO 供 Strings 使用。这样每批只动一处、也便于逐批复核。
 * 漏翻的 key 会自动回落到简体中文（不会崩，界面会显示中文）。
 */

// ===========================================================================
// 批次 0：底部导航 / 主页 / 设置主页 / 语言设置页
// ===========================================================================
private val B0_ZH = mapOf(
    "nav.home" to "主页",
    "nav.settings" to "设置",

    "home.subtitle" to "拍照搜题 · AI 解答 · 错题整理",
    "home.camera" to "📷  拍照搜题",
    "home.ask" to "✏️  图文提问",
    "camera.gradeToggle" to "批改",
    "home.grade" to "✏️  批改题目",
    "home.notebook" to "📚  错题本",
    "home.review" to "📖  复习",
    "home.key.title" to "配置 DeepSeek API Key",
    "home.key.hint" to "请输入你的 DeepSeek API Key（sk- 开头，加密保存在本机）",
    "home.key.save" to "保存",
    "home.key.later" to "稍后",

    "settings.title" to "设置",
    "settings.api" to "🔑 API 管理",
    "settings.lang" to "🌐 语言设置",
    "settings.theme" to "🎨 应用主题",
    "settings.notify" to "🔔 复习提醒",
    "settings.background" to "🔋 后台运行",
    "settings.changelog" to "📝 更新内容",
    "settings.about" to "ℹ️ 关于",

    "lang.title" to "🌐 语言设置",
    "lang.ui" to "应用语言",
    "lang.ui.desc" to "决定 App 界面文字的语言（立即生效，无需重启）",
    "lang.ai" to "AI 生成语言",
    "lang.ai.desc" to "决定 AI 用什么语言作答，影响拍题解答、直接提问、追问、批改、同类题；数学公式仍是 LaTeX。",
    "lang.followSystem" to "跟随系统",
    "lang.followQuestion" to "跟随题目"
)

private val B0_EN = mapOf(
    "nav.home" to "Home",
    "nav.settings" to "Settings",

    "home.subtitle" to "Photo search · AI solutions · Mistake notebook",
    "home.camera" to "📷  Solve by photo",
    "home.ask" to "✏️  Ask with text/image",
    "camera.gradeToggle" to "Grade",
    "home.grade" to "✏️  Grade my work",
    "home.notebook" to "📚  Notebook",
    "home.review" to "📖  Review",
    "home.key.title" to "Set up DeepSeek API Key",
    "home.key.hint" to "Paste your DeepSeek API Key (starts with sk-, encrypted and stored on this device)",
    "home.key.save" to "Save",
    "home.key.later" to "Later",

    "settings.title" to "Settings",
    "settings.api" to "🔑 API",
    "settings.lang" to "🌐 Language",
    "settings.theme" to "🎨 Theme",
    "settings.notify" to "🔔 Review reminder",
    "settings.background" to "🔋 Background running",
    "settings.changelog" to "📝 What's new",
    "settings.about" to "ℹ️ About",

    "lang.title" to "🌐 Language",
    "lang.ui" to "App language",
    "lang.ui.desc" to "Language of the app interface (applies immediately, no restart)",
    "lang.ai" to "AI answer language",
    "lang.ai.desc" to "Language the AI answers in. Applies to photo solving, direct questions, follow-ups, grading and similar problems. Math formulas stay in LaTeX.",
    "lang.followSystem" to "Follow system",
    "lang.followQuestion" to "Follow question"
)

private val B0_JA = mapOf(
    "nav.home" to "ホーム",
    "nav.settings" to "設定",

    "home.subtitle" to "撮影で検索 · AI 解答 · 間違いノート",
    "home.camera" to "📷  撮影して質問",
    "home.ask" to "✏️  文字/画像で質問",
    "camera.gradeToggle" to "添削",
    "home.grade" to "✏️  添削",
    "home.notebook" to "📚  間違いノート",
    "home.review" to "📖  復習",
    "home.key.title" to "DeepSeek API Key の設定",
    "home.key.hint" to "DeepSeek API Key を入力してください（sk- で始まる／端末内に暗号化して保存）",
    "home.key.save" to "保存",
    "home.key.later" to "後で",

    "settings.title" to "設定",
    "settings.api" to "🔑 API 管理",
    "settings.lang" to "🌐 言語設定",
    "settings.theme" to "🎨 テーマ",
    "settings.notify" to "🔔 復習リマインダー",
    "settings.background" to "🔋 バックグラウンド実行",
    "settings.changelog" to "📝 更新内容",
    "settings.about" to "ℹ️ このアプリについて",

    "lang.title" to "🌐 言語設定",
    "lang.ui" to "アプリの言語",
    "lang.ui.desc" to "アプリ画面の言語（すぐに反映されます／再起動不要）",
    "lang.ai" to "AI の回答言語",
    "lang.ai.desc" to "AI がどの言語で解答するかを決めます。撮影質問・直接質問・追加質問・添削・類似問題に適用。数式は LaTeX のままです。",
    "lang.followSystem" to "システムに従う",
    "lang.followQuestion" to "問題文に合わせる"
)

private val B0_KO = mapOf(
    "nav.home" to "홈",
    "nav.settings" to "설정",

    "home.subtitle" to "사진 검색 · AI 풀이 · 오답 노트",
    "home.camera" to "📷  사진으로 질문",
    "home.ask" to "✏️  글/이미지 질문",
    "camera.gradeToggle" to "첨삭",
    "home.grade" to "✏️  채점",
    "home.notebook" to "📚  오답 노트",
    "home.review" to "📖  복습",
    "home.key.title" to "DeepSeek API Key 설정",
    "home.key.hint" to "DeepSeek API Key를 입력하세요 (sk- 로 시작, 기기에 암호화되어 저장됩니다)",
    "home.key.save" to "저장",
    "home.key.later" to "나중에",

    "settings.title" to "설정",
    "settings.api" to "🔑 API 관리",
    "settings.lang" to "🌐 언어 설정",
    "settings.theme" to "🎨 테마",
    "settings.notify" to "🔔 복습 알림",
    "settings.background" to "🔋 백그라운드 실행",
    "settings.changelog" to "📝 업데이트 내역",
    "settings.about" to "ℹ️ 정보",

    "lang.title" to "🌐 언어 설정",
    "lang.ui" to "앱 언어",
    "lang.ui.desc" to "앱 화면 언어입니다 (즉시 적용, 재시작 불필요)",
    "lang.ai" to "AI 답변 언어",
    "lang.ai.desc" to "AI가 답변할 언어입니다. 사진 질문, 직접 질문, 추가 질문, 채점, 유사 문제에 적용되며 수식은 LaTeX로 표시됩니다.",
    "lang.followSystem" to "시스템 설정 따르기",
    "lang.followQuestion" to "문제 언어 따르기"
)

// ===========================================================================
// 批次 1：拍照页 / 直接提问页 / 框选页
// ===========================================================================
private val B1_ZH = mapOf(
    "common.back" to "返回",
    "common.backArrow" to "← 返回",

    "camera.permNeeded" to "需要相机权限才能拍照搜题",
    "camera.grantPerm" to "授予相机权限",
    "camera.errRead" to "读取图片失败：{msg}",
    "camera.errShot" to "拍照失败：{msg}",
    "camera.mode.one" to "单张",
    "camera.mode.two" to "两张",
    "camera.hint.twoFirst" to "两张模式：先拍第一张",
    "camera.hint.twoSecond" to "两张模式：请拍第二张",
    "camera.askDirect" to "✏️ 直接提问",

    "ask.title" to "直接提问",
    "ask.addImage" to "🖼 加图",
    "ask.placeholder" to "输入你的问题，可直接用文字提问…",
    "ask.imageDesc" to "附图",
    "ask.send" to "提问 AI",

    "crop.noImage" to "未找到图片，请重新拍照",
    "crop.backToCamera" to "回到拍摄界面",
    "crop.multiPos" to "第 {i}/{n} 张",
    "crop.wholeHoldHint" to "按住 2 秒后松手跳过 · 上滑取消",
    "crop.autoSelected" to "已自动框选题目范围，可拖动微调",
    "crop.aiDetect" to "🤖 AI 识别",
    "crop.aiBusy" to "识别中…",
    "crop.confirm" to "✅ 确认框选并解答",
    "crop.whole" to "整张图片（跳过框选）",
    "crop.reset" to "重置框选",
    "crop.reshoot" to "📷 重新拍摄"
)

private val B1_EN = mapOf(
    "common.back" to "Back",
    "common.backArrow" to "← Back",

    "camera.permNeeded" to "Camera permission is required to solve by photo",
    "camera.grantPerm" to "Grant camera permission",
    "camera.errRead" to "Could not read the image: {msg}",
    "camera.errShot" to "Could not take the photo: {msg}",
    "camera.mode.one" to "One",
    "camera.mode.two" to "Two",
    "camera.hint.twoFirst" to "Two-shot mode: take the first photo",
    "camera.hint.twoSecond" to "Two-shot mode: take the second photo",
    "camera.askDirect" to "✏️ Ask directly",

    "ask.title" to "Ask directly",
    "ask.addImage" to "🖼 Add image",
    "ask.placeholder" to "Type your question — text only is fine…",
    "ask.imageDesc" to "Attached image",
    "ask.send" to "Ask AI",

    "crop.noImage" to "Image not found, please take the photo again",
    "crop.backToCamera" to "Back to camera",
    "crop.multiPos" to "Image {i} of {n}",
    "crop.wholeHoldHint" to "Hold 2s, release to skip · slide up to cancel",
    "crop.autoSelected" to "Question area auto-selected — drag to fine-tune",
    "crop.aiDetect" to "🤖 AI detect",
    "crop.aiBusy" to "Detecting…",
    "crop.confirm" to "✅ Confirm selection & solve",
    "crop.whole" to "Whole image (skip cropping)",
    "crop.reset" to "Reset selection",
    "crop.reshoot" to "📷 Retake"
)

private val B1_JA = mapOf(
    "common.back" to "戻る",
    "common.backArrow" to "← 戻る",

    "camera.permNeeded" to "撮影して質問するにはカメラの権限が必要です",
    "camera.grantPerm" to "カメラ権限を許可",
    "camera.errRead" to "画像を読み込めませんでした：{msg}",
    "camera.errShot" to "撮影に失敗しました：{msg}",
    "camera.mode.one" to "1枚",
    "camera.mode.two" to "2枚",
    "camera.hint.twoFirst" to "2枚モード：まず1枚目を撮影",
    "camera.hint.twoSecond" to "2枚モード：2枚目を撮影してください",
    "camera.askDirect" to "✏️ 直接質問",

    "ask.title" to "直接質問",
    "ask.addImage" to "🖼 画像を追加",
    "ask.placeholder" to "質問を入力（文字だけでもOK）…",
    "ask.imageDesc" to "添付画像",
    "ask.send" to "AI に質問",

    "crop.noImage" to "画像が見つかりません。撮り直してください",
    "crop.backToCamera" to "撮影画面へ戻る",
    "crop.multiPos" to "{n} 枚中 {i} 枚目",
    "crop.wholeHoldHint" to "2 秒長押し→離してスキップ・上スワイプで取消",
    "crop.autoSelected" to "問題範囲を自動選択しました（ドラッグで微調整）",
    "crop.aiDetect" to "🤖 AI 認識",
    "crop.aiBusy" to "認識中…",
    "crop.confirm" to "✅ 範囲を確定して解答",
    "crop.whole" to "画像全体（切り抜きなし）",
    "crop.reset" to "範囲をリセット",
    "crop.reshoot" to "📷 撮り直す"
)

private val B1_KO = mapOf(
    "common.back" to "뒤로",
    "common.backArrow" to "← 뒤로",

    "camera.permNeeded" to "사진으로 질문하려면 카메라 권한이 필요합니다",
    "camera.grantPerm" to "카메라 권한 허용",
    "camera.errRead" to "이미지를 읽지 못했습니다: {msg}",
    "camera.errShot" to "촬영에 실패했습니다: {msg}",
    "camera.mode.one" to "1장",
    "camera.mode.two" to "2장",
    "camera.hint.twoFirst" to "2장 모드: 먼저 첫 번째 사진을 찍으세요",
    "camera.hint.twoSecond" to "2장 모드: 두 번째 사진을 찍으세요",
    "camera.askDirect" to "✏️ 직접 질문",

    "ask.title" to "직접 질문",
    "ask.addImage" to "🖼 이미지 추가",
    "ask.placeholder" to "질문을 입력하세요 (텍스트만으로도 가능)…",
    "ask.imageDesc" to "첨부 이미지",
    "ask.send" to "AI에게 질문",

    "crop.noImage" to "이미지를 찾을 수 없습니다. 다시 촬영하세요",
    "crop.backToCamera" to "촬영 화면으로",
    "crop.multiPos" to "{n}장 중 {i}장",
    "crop.wholeHoldHint" to "2초 누른 뒤 떼면 건너뛰기 · 위로 밀면 취소",
    "crop.autoSelected" to "문제 영역을 자동 선택했습니다 (드래그로 미세 조정)",
    "crop.aiDetect" to "🤖 AI 인식",
    "crop.aiBusy" to "인식 중…",
    "crop.confirm" to "✅ 선택 확정 후 풀이",
    "crop.whole" to "전체 이미지 (자르기 생략)",
    "crop.reset" to "선택 초기화",
    "crop.reshoot" to "📷 다시 촬영"
)

// ===========================================================================
// 批次 2：解题页（顶栏 / 对话框 / 追问栏 / 分类标签选择）
// ===========================================================================
private val B2_ZH = mapOf(
    "common.delete" to "删除",
    "common.cancel" to "取消",
    "common.save" to "保存",

    "solve.title" to "解题",
    "solve.selectedCount" to "已选 {n} 条",
    "solve.practiceSimilar" to "✏️ 练同类题",
    "solve.delete" to "🗑 删除",
    "solve.done" to "✓ 完成",
    "solve.edit" to "✏️ 编辑",
    "solve.restore" to "↩ 恢复",
    "solve.category" to "📁 {name}",
    "solve.category.none" to "📁 暂不分类",
    "solve.noCategory" to "暂不分类",
    "solve.regen" to "🔄 重新生成",
    "solve.savedToNotebook" to "📚 已存错题",
    "solve.saveToNotebook" to "📚 存错题本",
    "solve.deleteConfirm.title" to "确认删除",
    "solve.deleteConfirm.text" to "确定要将这道错题从错题本中删除吗？",
    "solve.deleteMessages.title" to "删除消息",
    "solve.deleteMessages.text" to "确定删除选中的 {n} 条消息吗？删除后 AI 会基于剩余消息继续对话。",
    "solve.catPicker.select" to "选择分类与标签",
    "solve.catPicker.edit" to "修改分类与标签",
    "solve.noKey" to "⚠️ 尚未配置 DeepSeek API Key",
    "solve.goSettings" to "去设置",
    "solve.gotIt" to "知道了",
    "solve.networkLost" to "网络连接似乎中断了",
    "solve.continueGen" to "继续生成",
    "solve.generating" to "答案生成中……",
    "solve.waitingQuestion" to "正在等待题目…\n（拍照后会出现题目与解答）",
    "solve.reviewDone" to "今日要复习的错题已全部完成 🎉",
    "solve.familiar" to "🌱 熟悉",
    "solve.vague" to "🌤 模糊",
    "solve.forgot" to "🔥 忘记",
    "solve.imageDesc" to "附图",
    "solve.followUpHint" to "继续追问（可带图）…",
    "solve.send" to "发送",
    "solve.cat.choose" to "请选择分类，或新建一个",
    "solve.cat.new" to "➕ 新建分类",
    "solve.cat.name" to "分类名",
    "solve.tags" to "知识点标签（最多 5 个）",
    "solve.tags.selected" to "选择标签（已选 {n}/5）",
    "solve.tags.empty" to "暂无已有标签",
    "solve.tags.new" to "新增标签",
    "solve.tags.newHint" to "如 分部积分",
    "solve.tags.add" to "加",
    "solve.textImageMark" to "[图]"
)

private val B2_EN = mapOf(
    "common.delete" to "Delete",
    "common.cancel" to "Cancel",
    "common.save" to "Save",

    "solve.title" to "Solve",
    "solve.selectedCount" to "{n} selected",
    "solve.practiceSimilar" to "✏️ Similar problem",
    "solve.delete" to "🗑 Delete",
    "solve.done" to "✓ Done",
    "solve.edit" to "✏️ Edit",
    "solve.restore" to "↩ Restore",
    "solve.category" to "📁 {name}",
    "solve.category.none" to "📁 Uncategorized",
    "solve.noCategory" to "Uncategorized",
    "solve.regen" to "🔄 Regenerate",
    "solve.savedToNotebook" to "📚 Saved",
    "solve.saveToNotebook" to "📚 Save to notebook",
    "solve.deleteConfirm.title" to "Confirm deletion",
    "solve.deleteConfirm.text" to "Remove this problem from the notebook?",
    "solve.deleteMessages.title" to "Delete messages",
    "solve.deleteMessages.text" to "Delete the {n} selected messages? The AI will continue from the remaining ones.",
    "solve.catPicker.select" to "Choose category & tags",
    "solve.catPicker.edit" to "Edit category & tags",
    "solve.noKey" to "⚠️ DeepSeek API Key is not set",
    "solve.goSettings" to "Open settings",
    "solve.gotIt" to "Got it",
    "solve.networkLost" to "The connection seems to have dropped",
    "solve.continueGen" to "Continue",
    "solve.generating" to "Generating the answer…",
    "solve.waitingQuestion" to "Waiting for the question…\n(it appears here after you take a photo)",
    "solve.reviewDone" to "All of today's review problems are done 🎉",
    "solve.familiar" to "🌱 Easy",
    "solve.vague" to "🌤 Unsure",
    "solve.forgot" to "🔥 Forgot",
    "solve.imageDesc" to "Attached image",
    "solve.followUpHint" to "Ask a follow-up (image optional)…",
    "solve.send" to "Send",
    "solve.cat.choose" to "Pick a category, or create a new one",
    "solve.cat.new" to "➕ New category",
    "solve.cat.name" to "Category name",
    "solve.tags" to "Knowledge tags (max 5)",
    "solve.tags.selected" to "Pick tags ({n}/5 selected)",
    "solve.tags.empty" to "No existing tags",
    "solve.tags.new" to "New tag",
    "solve.tags.newHint" to "e.g. integration by parts",
    "solve.tags.add" to "Add",
    "solve.textImageMark" to "[img]"
)

private val B2_JA = mapOf(
    "common.delete" to "削除",
    "common.cancel" to "キャンセル",
    "common.save" to "保存",

    "solve.title" to "解答",
    "solve.selectedCount" to "{n} 件を選択中",
    "solve.practiceSimilar" to "✏️ 類似問題",
    "solve.delete" to "🗑 削除",
    "solve.done" to "✓ 完了",
    "solve.edit" to "✏️ 編集",
    "solve.restore" to "↩ 復元",
    "solve.category" to "📁 {name}",
    "solve.category.none" to "📁 未分類",
    "solve.noCategory" to "未分類",
    "solve.regen" to "🔄 再生成",
    "solve.savedToNotebook" to "📚 保存済み",
    "solve.saveToNotebook" to "📚 ノートに保存",
    "solve.deleteConfirm.title" to "削除の確認",
    "solve.deleteConfirm.text" to "この問題を間違いノートから削除しますか？",
    "solve.deleteMessages.title" to "メッセージを削除",
    "solve.deleteMessages.text" to "選択した {n} 件を削除しますか？AI は残りのメッセージで会話を続けます。",
    "solve.catPicker.select" to "分類とタグを選択",
    "solve.catPicker.edit" to "分類とタグを変更",
    "solve.noKey" to "⚠️ DeepSeek API Key が未設定です",
    "solve.goSettings" to "設定へ",
    "solve.gotIt" to "了解",
    "solve.networkLost" to "接続が切断されたようです",
    "solve.continueGen" to "続きを生成",
    "solve.generating" to "解答を生成中……",
    "solve.waitingQuestion" to "問題を待っています…\n（撮影すると問題と解答が表示されます）",
    "solve.reviewDone" to "今日の復習はすべて完了しました 🎉",
    "solve.familiar" to "🌱 わかる",
    "solve.vague" to "🌤 あいまい",
    "solve.forgot" to "🔥 わからない",
    "solve.imageDesc" to "添付画像",
    "solve.followUpHint" to "追加質問（画像も可）…",
    "solve.send" to "送信",
    "solve.cat.choose" to "分類を選ぶか、新規作成してください",
    "solve.cat.new" to "➕ 分類を新規作成",
    "solve.cat.name" to "分類名",
    "solve.tags" to "知識タグ（最大 5 個）",
    "solve.tags.selected" to "タグを選択（{n}/5 選択中）",
    "solve.tags.empty" to "既存のタグはありません",
    "solve.tags.new" to "タグを追加",
    "solve.tags.newHint" to "例：部分積分",
    "solve.tags.add" to "追加",
    "solve.textImageMark" to "[画像]"
)

private val B2_KO = mapOf(
    "common.delete" to "삭제",
    "common.cancel" to "취소",
    "common.save" to "저장",

    "solve.title" to "풀이",
    "solve.selectedCount" to "{n}개 선택됨",
    "solve.practiceSimilar" to "✏️ 유사 문제",
    "solve.delete" to "🗑 삭제",
    "solve.done" to "✓ 완료",
    "solve.edit" to "✏️ 편집",
    "solve.restore" to "↩ 복원",
    "solve.category" to "📁 {name}",
    "solve.category.none" to "📁 미분류",
    "solve.noCategory" to "미분류",
    "solve.regen" to "🔄 다시 생성",
    "solve.savedToNotebook" to "📚 저장됨",
    "solve.saveToNotebook" to "📚 오답 노트에 저장",
    "solve.deleteConfirm.title" to "삭제 확인",
    "solve.deleteConfirm.text" to "이 문제를 오답 노트에서 삭제할까요?",
    "solve.deleteMessages.title" to "메시지 삭제",
    "solve.deleteMessages.text" to "선택한 {n}개 메시지를 삭제할까요? AI는 남은 메시지로 대화를 이어갑니다.",
    "solve.catPicker.select" to "분류와 태그 선택",
    "solve.catPicker.edit" to "분류와 태그 수정",
    "solve.noKey" to "⚠️ DeepSeek API Key가 설정되지 않았습니다",
    "solve.goSettings" to "설정으로",
    "solve.gotIt" to "확인",
    "solve.networkLost" to "연결이 끊긴 것 같습니다",
    "solve.continueGen" to "계속 생성",
    "solve.generating" to "답변 생성 중……",
    "solve.waitingQuestion" to "문제를 기다리는 중…\n(사진을 찍으면 문제와 풀이가 표시됩니다)",
    "solve.reviewDone" to "오늘 복습할 오답을 모두 끝냈습니다 🎉",
    "solve.familiar" to "🌱 익숙함",
    "solve.vague" to "🌤 애매함",
    "solve.forgot" to "🔥 모름",
    "solve.imageDesc" to "첨부 이미지",
    "solve.followUpHint" to "추가 질문 (이미지 가능)…",
    "solve.send" to "전송",
    "solve.cat.choose" to "분류를 선택하거나 새로 만드세요",
    "solve.cat.new" to "➕ 새 분류",
    "solve.cat.name" to "분류 이름",
    "solve.tags" to "지식 태그 (최대 5개)",
    "solve.tags.selected" to "태그 선택 ({n}/5 선택됨)",
    "solve.tags.empty" to "기존 태그 없음",
    "solve.tags.new" to "새 태그",
    "solve.tags.newHint" to "예: 부분적분",
    "solve.tags.add" to "추가",
    "solve.textImageMark" to "[이미지]"
)

// ===========================================================================
// 批次 3：批改页 / 练同类题页
// ===========================================================================
private val B3_ZH = mapOf(
    "grade.permNeeded" to "需要相机权限才能批改",
    "grade.hint.answer" to "请拍摄手写答案",
    "grade.hint.twoFirst" to "两张模式：先拍题目",
    "grade.hint.one" to "单张模式：拍题目",
    "grade.busy" to "批改中……",
    "grade.label.question" to "题目",
    "grade.label.answer" to "我的作答",
    "grade.retake" to "重拍",

    "similar.title" to "练同类题",
    "similar.showAnswer" to "👁 查看答案",
    "similar.generating" to "同类题生成中……",
    "similar.hint" to "继续追问…",
    "similar.answerPrefix" to "💡 答案\n\n{body}"
)

private val B3_EN = mapOf(
    "grade.permNeeded" to "Camera permission is required for grading",
    "grade.hint.answer" to "Now photograph your handwritten answer",
    "grade.hint.twoFirst" to "Two-shot mode: photograph the question first",
    "grade.hint.one" to "One-shot mode: photograph the question",
    "grade.busy" to "Grading…",
    "grade.label.question" to "Question",
    "grade.label.answer" to "My answer",
    "grade.retake" to "Retake",

    "similar.title" to "Similar problem",
    "similar.showAnswer" to "👁 Show answer",
    "similar.generating" to "Generating a similar problem…",
    "similar.hint" to "Ask more…",
    "similar.answerPrefix" to "💡 Answer\n\n{body}"
)

private val B3_JA = mapOf(
    "grade.permNeeded" to "添削にはカメラの権限が必要です",
    "grade.hint.answer" to "手書きの解答を撮影してください",
    "grade.hint.twoFirst" to "2枚モード：まず問題を撮影",
    "grade.hint.one" to "1枚モード：問題を撮影",
    "grade.busy" to "添削中……",
    "grade.label.question" to "問題",
    "grade.label.answer" to "自分の解答",
    "grade.retake" to "撮り直す",

    "similar.title" to "類似問題",
    "similar.showAnswer" to "👁 解答を見る",
    "similar.generating" to "類似問題を生成中……",
    "similar.hint" to "追加で質問…",
    "similar.answerPrefix" to "💡 解答\n\n{body}"
)

private val B3_KO = mapOf(
    "grade.permNeeded" to "채점하려면 카메라 권한이 필요합니다",
    "grade.hint.answer" to "손으로 쓴 답안을 촬영하세요",
    "grade.hint.twoFirst" to "2장 모드: 먼저 문제를 촬영하세요",
    "grade.hint.one" to "1장 모드: 문제를 촬영하세요",
    "grade.busy" to "채점 중……",
    "grade.label.question" to "문제",
    "grade.label.answer" to "내 답안",
    "grade.retake" to "다시 촬영",

    "similar.title" to "유사 문제",
    "similar.showAnswer" to "👁 정답 보기",
    "similar.generating" to "유사 문제 생성 중……",
    "similar.hint" to "추가 질문…",
    "similar.answerPrefix" to "💡 정답\n\n{body}"
)

// ===========================================================================
// 批次 4：错题本 / 复习页
// ===========================================================================
private val B4_ZH = mapOf(
    "notebook.title" to "错题本（{n}）",
    "notebook.selectedCount" to "已选 {n} 项",
    "notebook.category" to "📁 分类",
    "notebook.filter.all" to "全部",
    "notebook.filter.uncategorized" to "未分类",
    "notebook.filter.tags" to "按标签搜索",
    "notebook.filter.tagsSelected" to "按标签搜索（已选 {n}）",
    "notebook.empty.uncategorized" to "还没有「未分类」的错题",
    "notebook.empty.category" to "该分类下暂无错题",
    "notebook.empty.tag" to "该标签下暂无错题",
    "notebook.empty.default" to "还没有错题，去拍照搜题吧 📷",
    "notebook.batchCategory" to "批量设置分类",
    "notebook.purge.title" to "彻底删除",
    "notebook.purge.text" to "确定要彻底删除选中的 {n} 项吗？此操作不可恢复。",
    "notebook.imageDesc" to "错题原图",

    "review.title" to "复习",
    "review.tab.today" to "今天",
    "review.tab.week" to "本周",
    "review.empty.today" to "今天没有要复习的错题 🎉",
    "review.empty.week" to "本周没有要复习的错题 🎉",
    "review.imageDesc" to "复习题原图"
)

private val B4_EN = mapOf(
    "notebook.title" to "Notebook ({n})",
    "notebook.selectedCount" to "{n} selected",
    "notebook.category" to "📁 Category",
    "notebook.filter.all" to "All",
    "notebook.filter.uncategorized" to "Uncategorized",
    "notebook.filter.tags" to "Search by tag",
    "notebook.filter.tagsSelected" to "Search by tag ({n} selected)",
    "notebook.empty.uncategorized" to "No uncategorized problems yet",
    "notebook.empty.category" to "No problems in this category",
    "notebook.empty.tag" to "No problems with this tag",
    "notebook.empty.default" to "No problems yet — solve one by photo 📷",
    "notebook.batchCategory" to "Set category for selected",
    "notebook.purge.title" to "Delete permanently",
    "notebook.purge.text" to "Permanently delete the {n} selected items? This cannot be undone.",
    "notebook.imageDesc" to "Problem image",

    "review.title" to "Review",
    "review.tab.today" to "Today",
    "review.tab.week" to "This week",
    "review.empty.today" to "Nothing to review today 🎉",
    "review.empty.week" to "Nothing to review this week 🎉",
    "review.imageDesc" to "Review image"
)

private val B4_JA = mapOf(
    "notebook.title" to "間違いノート（{n}）",
    "notebook.selectedCount" to "{n} 件選択中",
    "notebook.category" to "📁 分類",
    "notebook.filter.all" to "すべて",
    "notebook.filter.uncategorized" to "未分類",
    "notebook.filter.tags" to "タグで検索",
    "notebook.filter.tagsSelected" to "タグで検索（{n} 件選択中）",
    "notebook.empty.uncategorized" to "「未分類」の問題はまだありません",
    "notebook.empty.category" to "この分類にはまだ問題がありません",
    "notebook.empty.tag" to "このタグにはまだ問題がありません",
    "notebook.empty.default" to "まだ問題がありません。撮影して質問しましょう 📷",
    "notebook.batchCategory" to "選択項目の分類を設定",
    "notebook.purge.title" to "完全に削除",
    "notebook.purge.text" to "選択した {n} 件を完全に削除しますか？元に戻せません。",
    "notebook.imageDesc" to "問題の画像",

    "review.title" to "復習",
    "review.tab.today" to "今日",
    "review.tab.week" to "今週",
    "review.empty.today" to "今日の復習はありません 🎉",
    "review.empty.week" to "今週の復習はありません 🎉",
    "review.imageDesc" to "復習問題の画像"
)

private val B4_KO = mapOf(
    "notebook.title" to "오답 노트 ({n})",
    "notebook.selectedCount" to "{n}개 선택됨",
    "notebook.category" to "📁 분류",
    "notebook.filter.all" to "전체",
    "notebook.filter.uncategorized" to "미분류",
    "notebook.filter.tags" to "태그로 검색",
    "notebook.filter.tagsSelected" to "태그로 검색 ({n}개 선택)",
    "notebook.empty.uncategorized" to "'미분류' 문제가 아직 없습니다",
    "notebook.empty.category" to "이 분류에는 문제가 없습니다",
    "notebook.empty.tag" to "이 태그에는 문제가 없습니다",
    "notebook.empty.default" to "아직 문제가 없습니다. 사진으로 질문해 보세요 📷",
    "notebook.batchCategory" to "선택 항목 분류 설정",
    "notebook.purge.title" to "완전 삭제",
    "notebook.purge.text" to "선택한 {n}개를 완전히 삭제할까요? 되돌릴 수 없습니다.",
    "notebook.imageDesc" to "문제 이미지",

    "review.title" to "복습",
    "review.tab.today" to "오늘",
    "review.tab.week" to "이번 주",
    "review.empty.today" to "오늘 복습할 오답이 없습니다 🎉",
    "review.empty.week" to "이번 주 복습할 오답이 없습니다 🎉",
    "review.imageDesc" to "복습 문제 이미지"
)

// ===========================================================================
// 批次 5：设置各子页（API Key / 主题 / 提醒 / 后台 / 关于）+ 时间选择器
// ===========================================================================
private val B5_ZH = mapOf(
    "common.ok" to "确定",

    "settings.api.configured" to "已配置（粘贴新的可覆盖）",
    "settings.api.notConfigured" to "尚未配置，请粘贴你的 DeepSeek API Key（sk- 开头）",
    "settings.api.saved" to "✅ 已保存（加密存储，仅本机）",

    "theme.option.system" to "跟随系统",
    "theme.option.light" to "浅色",
    "theme.option.dark" to "深色",

    "notify.enable" to "开启每日提醒",
    "notify.time" to "提醒时间",
    "notify.hint" to "到点会提醒：今天还有 xx 道错题要复习！本周末前还有 xx 道！",

    "background.allow" to "允许后台运行（不漏提醒）",
    "background.on" to "✅ 已开启",
    "background.goEnable" to "去开启",
    "background.tip" to "提示：国产手机（荣耀/华为等）还需在「手机管家 → 应用 → 自启动/后台运行」中允许本应用，才能在关闭后仍收到提醒、后台生成不中断。",
    "background.autostart" to "自启动/后台运行权限",
    "background.oneTap" to "一键开启",

    "about.version" to "版本 {v}",
    "about.author" to "作者：zsz",
    "about.desc" to "本应用由 DeepSeek-V4/V4.1 辅助编写，用于拍照搜题、AI 解答与错题整理。",

    "timePicker.title" to "设置提醒时间"
)

private val B5_EN = mapOf(
    "common.ok" to "OK",

    "settings.api.configured" to "Configured (paste a new key to replace)",
    "settings.api.notConfigured" to "Not configured yet — paste your DeepSeek API Key (starts with sk-)",
    "settings.api.saved" to "✅ Saved (encrypted, this device only)",

    "theme.option.system" to "Follow system",
    "theme.option.light" to "Light",
    "theme.option.dark" to "Dark",

    "notify.enable" to "Daily reminder",
    "notify.time" to "Reminder time",
    "notify.hint" to "You will be reminded: xx problems to review today, xx by the end of this week!",

    "background.allow" to "Allow background running (don't miss reminders)",
    "background.on" to "✅ Enabled",
    "background.goEnable" to "Enable",
    "background.tip" to "Tip: on Chinese phones (Honor/Huawei and others) you also need to allow this app under \"Phone Manager → Apps → Auto-start / background running\", otherwise reminders may not arrive after the app is closed.",
    "background.autostart" to "Auto-start / background permission",
    "background.oneTap" to "Open settings",

    "about.version" to "Version {v}",
    "about.author" to "Author: zsz",
    "about.desc" to "Built with the help of DeepSeek-V4/V4.1, for photo problem search, AI solutions and mistake notes.",

    "timePicker.title" to "Reminder time"
)

private val B5_JA = mapOf(
    "common.ok" to "OK",

    "settings.api.configured" to "設定済み（新しいキーを貼り付けると上書き）",
    "settings.api.notConfigured" to "未設定です。DeepSeek API Key（sk- で始まる）を貼り付けてください",
    "settings.api.saved" to "✅ 保存しました（暗号化・本機のみ）",

    "theme.option.system" to "システムに従う",
    "theme.option.light" to "ライト",
    "theme.option.dark" to "ダーク",

    "notify.enable" to "毎日のリマインダー",
    "notify.time" to "リマインダー時刻",
    "notify.hint" to "時間になると通知します：今日の復習は xx 問、今週末までに xx 問！",

    "background.allow" to "バックグラウンド実行を許可（通知を逃さない）",
    "background.on" to "✅ 有効",
    "background.goEnable" to "許可する",
    "background.tip" to "ヒント：中国メーカーの端末（Honor/Huawei など）では「スマホマネージャー → アプリ → 自動起動／バックグラウンド実行」でも許可しないと、アプリを閉じた後に通知が届きません。",
    "background.autostart" to "自動起動／バックグラウンド権限",
    "background.oneTap" to "設定を開く",

    "about.version" to "バージョン {v}",
    "about.author" to "作者：zsz",
    "about.desc" to "本アプリは DeepSeek-V4/V4.1 の支援で作成しました。撮影検索・AI 解答・間違い整理に使えます。",

    "timePicker.title" to "リマインダー時刻"
)

private val B5_KO = mapOf(
    "common.ok" to "확인",

    "settings.api.configured" to "설정됨 (새 키를 붙여넣으면 덮어씁니다)",
    "settings.api.notConfigured" to "아직 설정되지 않았습니다. DeepSeek API Key(sk- 로 시작)를 붙여넣으세요",
    "settings.api.saved" to "✅ 저장됨 (암호화, 기기 내 저장)",

    "theme.option.system" to "시스템 설정 따르기",
    "theme.option.light" to "라이트",
    "theme.option.dark" to "다크",

    "notify.enable" to "매일 알림",
    "notify.time" to "알림 시간",
    "notify.hint" to "알림: 오늘 복습할 오답 xx개, 이번 주말까지 xx개!",

    "background.allow" to "백그라운드 실행 허용 (알림 누락 방지)",
    "background.on" to "✅ 사용 중",
    "background.goEnable" to "허용하기",
    "background.tip" to "참고: 중국 제조사 기기(Honor/Huawei 등)는 '폰 매니저 → 앱 → 자동 시작/백그라운드 실행'에서도 허용해야 앱을 닫은 뒤에도 알림이 도착합니다.",
    "background.autostart" to "자동 시작/백그라운드 권한",
    "background.oneTap" to "설정 열기",

    "about.version" to "버전 {v}",
    "about.author" to "만든이: zsz",
    "about.desc" to "DeepSeek-V4/V4.1의 도움으로 제작한 앱입니다. 사진 검색, AI 풀이, 오답 정리에 사용합니다.",

    "timePicker.title" to "알림 시간"
)

// ===========================================================================
// 批次 6：通知文案 + 错误提示
// ===========================================================================
private val B6_ZH = mapOf(
    "notif.review.channel" to "复习提醒",
    "notif.review.text" to "今天还有 {today} 道错题要复习！本周末前还有 {week} 道！",
    "notif.answer.channel" to "生成答案",
    "notif.answer.text" to "正在生成答案…（点击回到生成页面）",

    "err.timeout" to "请求超时了，请检查网络后重试",
    "err.connect" to "无法连接到服务器，请检查网络",
    "err.apikey" to "API Key 无效或未配置，请到⚙️设置检查",
    "err.requestFailed" to "请求失败",
    "err.similarFailed" to "出题失败：{msg}",
    "err.chatFailed" to "出错：{msg}",
    "err.gradeFailed" to "批改失败：{msg}",
    "err.emptyReply" to "AI 没有返回内容，请重试",
    "solve.tagsHint" to "\n\n💡 核心知识点/难点："
)

private val B6_EN = mapOf(
    "notif.review.channel" to "Review reminder",
    "notif.review.text" to "You have {today} problems to review today and {week} by the end of this week!",
    "notif.answer.channel" to "Generating answer",
    "notif.answer.text" to "Generating the answer… (tap to return)",

    "err.timeout" to "The request timed out — check your network and try again",
    "err.connect" to "Cannot reach the server — check your network",
    "err.apikey" to "API Key is invalid or missing — check ⚙️ Settings",
    "err.requestFailed" to "Request failed",
    "err.similarFailed" to "Could not generate a problem: {msg}",
    "err.chatFailed" to "Error: {msg}",
    "err.gradeFailed" to "Grading failed: {msg}",
    "err.emptyReply" to "The AI returned nothing — please try again",
    "solve.tagsHint" to "\n\n💡 Key concepts: "
)

private val B6_JA = mapOf(
    "notif.review.channel" to "復習リマインダー",
    "notif.review.text" to "今日の復習は {today} 問、今週末までに {week} 問あります！",
    "notif.answer.channel" to "解答を生成中",
    "notif.answer.text" to "解答を生成しています…（タップで戻る）",

    "err.timeout" to "リクエストがタイムアウトしました。ネットワークを確認してください",
    "err.connect" to "サーバーに接続できません。ネットワークを確認してください",
    "err.apikey" to "API Key が無効か未設定です。⚙️設定 を確認してください",
    "err.requestFailed" to "リクエストに失敗しました",
    "err.similarFailed" to "出題に失敗しました：{msg}",
    "err.chatFailed" to "エラー：{msg}",
    "err.gradeFailed" to "添削に失敗しました：{msg}",
    "err.emptyReply" to "AI から内容が返りませんでした。もう一度お試しください",
    "solve.tagsHint" to "\n\n💡 重要ポイント："
)

private val B6_KO = mapOf(
    "notif.review.channel" to "복습 알림",
    "notif.review.text" to "오늘 복습할 오답 {today}개, 이번 주말까지 {week}개 있습니다!",
    "notif.answer.channel" to "답변 생성 중",
    "notif.answer.text" to "답변을 생성하는 중… (탭하면 돌아갑니다)",

    "err.timeout" to "요청 시간이 초과되었습니다. 네트워크를 확인하세요",
    "err.connect" to "서버에 연결할 수 없습니다. 네트워크를 확인하세요",
    "err.apikey" to "API Key가 유효하지 않거나 없습니다. ⚙️설정을 확인하세요",
    "err.requestFailed" to "요청 실패",
    "err.similarFailed" to "문제 생성 실패: {msg}",
    "err.chatFailed" to "오류: {msg}",
    "err.gradeFailed" to "채점 실패: {msg}",
    "err.emptyReply" to "AI가 내용을 반환하지 않았습니다. 다시 시도하세요",
    "solve.tagsHint" to "\n\n💡 핵심 개념: "
)

// ===========================================================================
// 批次 7：设置重构（API 管理 / 数据管理 / 关于） + 复习折叠 + 搜索 + 公式条
// ===========================================================================
private val B7_ZH = mapOf(
    "settings.data" to "💾 数据管理",
    "about.changelog" to "更新内容",
    "about.info" to "应用信息",

    "api.key.note" to "API Key 一律加密保存在本地",
    "api.usage" to "用量统计",
    "api.usage.calls" to "累计调用 {n} 次",
    "api.usage.tokens" to "累计 tokens：输入 {i} / 输出 {o}",
    "api.usage.empty" to "暂无调用记录",
    "api.usage.reset" to "重置统计",
    "api.usage.resetDone" to "已重置统计",

    "data.summary" to "当前 {q} 道错题 · {c} 个分类 · {t} 个标签",
    "data.export" to "导出备份",
    "data.export.desc" to "导出为 JSON 文件（含题目图片），可保存到网盘或电脑",
    "data.import" to "导入备份",
    "data.import.desc" to "从 JSON 备份恢复：同名分类/标签会复用，题干相同的题目跳过",
    "data.clear" to "清空所有数据",
    "data.clear.desc" to "删除全部错题、分类、标签与复习进度（不可恢复）",
    "data.clear.title" to "确认清空",
    "data.clear.text" to "将删除全部 {n} 道错题及其分类/标签/复习进度，且无法恢复。建议先导出备份。",
    "data.clear.inputLabel" to "请输入下面的文字以确认：",
    "data.clear.phrase" to "我同意删除本应用所有数据",
    "data.exporting" to "正在导出…",
    "data.importing" to "正在导入…",
    "data.exportDone" to "已导出 {n} 道错题",
    "data.importDone" to "导入完成：新增 {q} 道错题、{c} 个分类、{t} 个标签，跳过重复 {s} 道",
    "data.failed" to "操作失败：{msg}",
    "data.clearDone" to "已清空所有数据",

    "review.progress" to "第 {i}/{n} 题",
    "review.showAnswer" to "👁 看解答",
    "review.hideAnswer" to "🙈 收起解答",

    "notebook.search" to "搜索题干或答案…",
    "formula.title" to "公式键盘"
)

private val B7_EN = mapOf(
    "settings.data" to "💾 Data",
    "about.changelog" to "What's new",
    "about.info" to "App info",

    "api.key.note" to "API Key is always stored encrypted on this device",
    "api.usage" to "Usage",
    "api.usage.calls" to "{n} calls in total",
    "api.usage.tokens" to "Total tokens: {i} in / {o} out",
    "api.usage.empty" to "No calls recorded yet",
    "api.usage.reset" to "Reset stats",
    "api.usage.resetDone" to "Stats reset",

    "data.summary" to "{q} problems · {c} categories · {t} tags",
    "data.export" to "Export backup",
    "data.export.desc" to "Export as JSON (images included); save it to cloud storage or a computer",
    "data.import" to "Import backup",
    "data.import.desc" to "Restore from a JSON backup: same-name categories/tags are reused, identical questions are skipped",
    "data.clear" to "Erase all data",
    "data.clear.desc" to "Delete all problems, categories, tags and review progress (cannot be undone)",
    "data.clear.title" to "Confirm erase",
    "data.clear.text" to "This deletes all {n} problems with their categories, tags and review progress. It cannot be undone — export a backup first.",
    "data.clear.inputLabel" to "Type the text below to confirm:",
    "data.clear.phrase" to "I agree to delete all data of this app",
    "data.exporting" to "Exporting…",
    "data.importing" to "Importing…",
    "data.exportDone" to "Exported {n} problems",
    "data.importDone" to "Import done: {q} problems, {c} categories, {t} tags added; {s} duplicates skipped",
    "data.failed" to "Failed: {msg}",
    "data.clearDone" to "All data erased",

    "review.progress" to "{i} / {n}",
    "review.showAnswer" to "👁 Show answer",
    "review.hideAnswer" to "🙈 Hide answer",

    "notebook.search" to "Search question or answer…",
    "formula.title" to "Formula keyboard"
)

private val B7_JA = mapOf(
    "settings.data" to "💾 データ管理",
    "about.changelog" to "更新内容",
    "about.info" to "アプリ情報",

    "api.key.note" to "API Key は常に端末内に暗号化して保存されます",
    "api.usage" to "利用状況",
    "api.usage.calls" to "累計 {n} 回",
    "api.usage.tokens" to "累計 tokens：入力 {i} / 出力 {o}",
    "api.usage.empty" to "まだ記録がありません",
    "api.usage.reset" to "統計をリセット",
    "api.usage.resetDone" to "統計をリセットしました",

    "data.summary" to "現在 {q} 問・分類 {c} 件・タグ {t} 件",
    "data.export" to "バックアップを書き出す",
    "data.export.desc" to "JSON（画像を含む）で書き出します。クラウドや PC に保存してください",
    "data.import" to "バックアップを読み込む",
    "data.import.desc" to "JSON から復元：同名の分類/タグは再利用し、同じ問題文はスキップします",
    "data.clear" to "すべてのデータを削除",
    "data.clear.desc" to "問題・分類・タグ・復習状況をすべて削除します（復元できません）",
    "data.clear.title" to "削除の確認",
    "data.clear.text" to "{n} 問すべてと分類・タグ・復習状況を削除します。復元できません。先にバックアップを書き出してください。",
    "data.clear.inputLabel" to "確認のため次の文字を入力してください：",
    "data.clear.phrase" to "本アプリのすべてのデータを削除することに同意します",
    "data.exporting" to "書き出し中…",
    "data.importing" to "読み込み中…",
    "data.exportDone" to "{n} 問を書き出しました",
    "data.importDone" to "完了：問題 {q} 件・分類 {c} 件・タグ {t} 件を追加、重複 {s} 件をスキップ",
    "data.failed" to "失敗しました：{msg}",
    "data.clearDone" to "すべてのデータを削除しました",

    "review.progress" to "{i} / {n} 問目",
    "review.showAnswer" to "👁 解答を見る",
    "review.hideAnswer" to "🙈 解答を隠す",

    "notebook.search" to "問題文・解答を検索…",
    "formula.title" to "数式キーボード"
)

private val B7_KO = mapOf(
    "settings.data" to "💾 데이터 관리",
    "about.changelog" to "업데이트 내역",
    "about.info" to "앱 정보",

    "api.key.note" to "API Key는 항상 기기 내에 암호화되어 저장됩니다",
    "api.usage" to "사용량",
    "api.usage.calls" to "누적 {n}회 호출",
    "api.usage.tokens" to "누적 tokens: 입력 {i} / 출력 {o}",
    "api.usage.empty" to "아직 기록이 없습니다",
    "api.usage.reset" to "통계 초기화",
    "api.usage.resetDone" to "통계를 초기화했습니다",

    "data.summary" to "현재 오답 {q}개 · 분류 {c}개 · 태그 {t}개",
    "data.export" to "백업 내보내기",
    "data.export.desc" to "JSON(이미지 포함)으로 내보냅니다. 클라우드나 PC에 저장하세요",
    "data.import" to "백업 가져오기",
    "data.import.desc" to "JSON에서 복원: 같은 이름의 분류/태그는 재사용하고, 문제 문장이 같은 항목은 건너뜁니다",
    "data.clear" to "모든 데이터 삭제",
    "data.clear.desc" to "모든 오답, 분류, 태그, 복습 진행을 삭제합니다(복구 불가)",
    "data.clear.title" to "삭제 확인",
    "data.clear.text" to "오답 {n}개와 분류/태그/복습 진행을 모두 삭제합니다. 복구할 수 없습니다. 먼저 백업을 내보내세요.",
    "data.clear.inputLabel" to "확인을 위해 아래 문장을 입력하세요:",
    "data.clear.phrase" to "이 앱의 모든 데이터 삭제에 동의합니다",
    "data.exporting" to "내보내는 중…",
    "data.importing" to "가져오는 중…",
    "data.exportDone" to "오답 {n}개를 내보냈습니다",
    "data.importDone" to "완료: 오답 {q}개, 분류 {c}개, 태그 {t}개 추가, 중복 {s}개 건너뜀",
    "data.failed" to "실패: {msg}",
    "data.clearDone" to "모든 데이터를 삭제했습니다",

    "review.progress" to "{i} / {n}",
    "review.showAnswer" to "👁 정답 보기",
    "review.hideAnswer" to "🙈 정답 숨기기",

    "notebook.search" to "문제나 정답 검색…",
    "formula.title" to "수식 키보드"
)

// ===========================================================================
// 批次 8：错题本（搜索改为按钮唤出 + 科目管理）
// ===========================================================================
private val B8_ZH = mapOf(
    "notebook.manage" to "管理",
    "notebook.searchToggle" to "搜索",

    "catManage.title" to "科目管理",
    "catManage.hint" to "点 ✏️ 重命名；勾选后可批量删除",
    "catManage.empty" to "还没有任何科目",
    "catManage.renameTitle" to "重命名科目",
    "catManage.renameHint" to "新名称",
    "catManage.delete" to "删除选中（{n}）",
    "catManage.deleteTitle" to "确认删除科目",
    "catManage.deleteText" to "将删除 {n} 个科目，此操作不可恢复。",
    "catManage.deleteQuestions" to "同时删除这些科目中的错题（不勾选则题目改为「未分类」）",
    "catManage.deleted" to "已删除 {n} 个科目"
)

private val B8_EN = mapOf(
    "notebook.manage" to "Manage",
    "notebook.searchToggle" to "Search",

    "catManage.title" to "Manage categories",
    "catManage.hint" to "Tap ✏️ to rename; select several to delete",
    "catManage.empty" to "No categories yet",
    "catManage.renameTitle" to "Rename category",
    "catManage.renameHint" to "New name",
    "catManage.delete" to "Delete selected ({n})",
    "catManage.deleteTitle" to "Delete categories",
    "catManage.deleteText" to "This will delete {n} categories and cannot be undone.",
    "catManage.deleteQuestions" to "Also delete the problems in these categories (otherwise they become Uncategorized)",
    "catManage.deleted" to "Deleted {n} categories"
)

private val B8_JA = mapOf(
    "notebook.manage" to "管理",
    "notebook.searchToggle" to "検索",

    "catManage.title" to "分類の管理",
    "catManage.hint" to "✏️ で名前を変更、選択してまとめて削除",
    "catManage.empty" to "分類がまだありません",
    "catManage.renameTitle" to "分類名を変更",
    "catManage.renameHint" to "新しい名前",
    "catManage.delete" to "選択を削除（{n}）",
    "catManage.deleteTitle" to "分類の削除",
    "catManage.deleteText" to "{n} 件の分類を削除します。元に戻せません。",
    "catManage.deleteQuestions" to "この分類の問題も一緒に削除する（チェックしない場合は「未分類」になります）",
    "catManage.deleted" to "{n} 件の分類を削除しました"
)

private val B8_KO = mapOf(
    "notebook.manage" to "관리",
    "notebook.searchToggle" to "검색",

    "catManage.title" to "분류 관리",
    "catManage.hint" to "✏️를 눌러 이름 변경, 선택 후 일괄 삭제",
    "catManage.empty" to "분류가 아직 없습니다",
    "catManage.renameTitle" to "분류 이름 변경",
    "catManage.renameHint" to "새 이름",
    "catManage.delete" to "선택 삭제 ({n})",
    "catManage.deleteTitle" to "분류 삭제",
    "catManage.deleteText" to "분류 {n}개를 삭제합니다. 되돌릴 수 없습니다.",
    "catManage.deleteQuestions" to "이 분류의 문제도 함께 삭제 (선택하지 않으면 '미분류'로 바뀝니다)",
    "catManage.deleted" to "분류 {n}개를 삭제했습니다"
)

// ===========================================================================
// 批次 9：流式生成（中止 / 继续）
// ===========================================================================
private val B9_ZH = mapOf(
    "solve.abort" to "⏸ 中止生成",
    "solve.continue" to "继续生成",
    "solve.selectAll" to "全选",
    "solve.deselectAll" to "取消全选",
    "solve.mistakeTitle" to "错题",
    "solve.gradeTitle" to "批改",
    "solve.regrade" to "🔄 重新批改",
    "solve.abortGrade" to "⏸ 中止批改",
    "solve.thinking" to "思考中…",
    "review.titleProgress" to "复习 {i}/{n}",
    "review.nextQuestion" to "下一题"
)

private val B9_EN = mapOf(
    "solve.abort" to "⏸ Stop",
    "solve.continue" to "Continue generating",
    "solve.selectAll" to "Select all",
    "solve.deselectAll" to "Deselect all",
    "solve.mistakeTitle" to "Problem",
    "solve.gradeTitle" to "Grading",
    "solve.regrade" to "🔄 Re-grade",
    "solve.abortGrade" to "⏸ Stop grading",
    "solve.thinking" to "Thinking…",
    "review.titleProgress" to "Review {i}/{n}",
    "review.nextQuestion" to "Next"
)

private val B9_JA = mapOf(
    "solve.abort" to "⏸ 中止",
    "solve.continue" to "生成を続ける",
    "solve.selectAll" to "すべて選択",
    "solve.deselectAll" to "選択解除",
    "solve.mistakeTitle" to "誤答",
    "solve.gradeTitle" to "添削",
    "solve.regrade" to "🔄 再添削",
    "solve.abortGrade" to "⏸ 添削を中止",
    "solve.thinking" to "考え中…",
    "review.titleProgress" to "復習 {i}/{n}",
    "review.nextQuestion" to "次の問題"
)

private val B9_KO = mapOf(
    "solve.abort" to "⏸ 중단",
    "solve.continue" to "생성 계속",
    "solve.selectAll" to "전체 선택",
    "solve.deselectAll" to "선택 해제",
    "solve.mistakeTitle" to "오답",
    "solve.gradeTitle" to "첨삭",
    "solve.regrade" to "🔄 다시 첨삭",
    "solve.abortGrade" to "⏸ 첨삭 중단",
    "solve.thinking" to "생각 중…",
    "review.titleProgress" to "복습 {i}/{n}",
    "review.nextQuestion" to "다음 문제"
)

// ===========================================================================
// 合并（每批新增后在这里追加对应的 Bn_xx）
// ===========================================================================
internal val ZH: Map<String, String> = B0_ZH + B1_ZH + B2_ZH + B3_ZH + B4_ZH + B5_ZH + B6_ZH + B7_ZH + B8_ZH + B9_ZH
internal val EN: Map<String, String> = B0_EN + B1_EN + B2_EN + B3_EN + B4_EN + B5_EN + B6_EN + B7_EN + B8_EN + B9_EN
internal val JA: Map<String, String> = B0_JA + B1_JA + B2_JA + B3_JA + B4_JA + B5_JA + B6_JA + B7_JA + B8_JA + B9_JA
internal val KO: Map<String, String> = B0_KO + B1_KO + B2_KO + B3_KO + B4_KO + B5_KO + B6_KO + B7_KO + B8_KO + B9_KO
