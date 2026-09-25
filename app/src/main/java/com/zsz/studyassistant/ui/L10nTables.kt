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
    "nav.learn" to "学习",
    "nav.stats" to "统计",
    "stats.title" to "📊 学习统计",
    "stats.last30" to "近 1 个月新增错题",
    "stats.month" to "1 个月",
    "stats.daysAgo30" to "29 天前",
    "stats.today" to "今日复习",
    "stats.week" to "近 7 天",
    "stats.streak" to "连续天数",
    "stats.due" to "待复习",
    "stats.mastery" to "掌握度分布",
    "stats.last7" to "近 7 天新增错题",
    "stats.subjects" to "科目分布",
    "stats.total" to "共 {n} 题",
    "stats.empty" to "还没有错题，去拍一道吧",
    "stats.daysAgo7" to "6 天前",
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
    "settings.group.general" to "通用",
    "settings.group.personalize" to "个性化",
    "settings.group.notifyBackground" to "🔔 通知与后台",
    "settings.aiCrop" to "🤖 AI 配置",
    "settings.aiCrop.timeout" to "✂️ AI 框选时限",
    "settings.customPrompt" to "✍️ 自定义要求（Prompt）",
    "settings.customPrompt.desc" to "写在这里的要求会附加到每次 AI 提问中。例如：只给思路不要直接给答案 / 步骤编号并写清依据 / 用更简洁的语言 / 优先用我课本的符号习惯…（留空即不生效）",
    "settings.solveModel" to "🧠 解题模式",
    "settings.solveModel.desc" to "Flash：快且省，日常首选；Reasoner：先推理再作答，难题更准但更慢、更耗 token",
    "settings.solveModel.flash" to "Flash（快 / 省）",
    "settings.solveModel.reasoner" to "Reasoner（慢 / 准）",
    "settings.aiCrop.desc" to "进框选页后等 AI 自动框选的上限；超过这个时间还没返回，就改用本地算法框选。",
    "settings.aiCrop.snapHint" to "磁吸点：500 ms · 1 s · 3 s",
    "settings.lang" to "🌐 语言设置",
    "settings.theme" to "🎨 应用主题",
    "settings.theme.mode" to "主题模式",
    "settings.theme.color" to "主题配色",
    "settings.theme.custom" to "自定义颜色",
    "settings.theme.hue" to "色相",
    "settings.theme.sat" to "饱和度",
    "settings.theme.value" to "明度",
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
    "nav.learn" to "Learn",
    "nav.stats" to "Stats",
    "stats.title" to "📊 Learning stats",
    "stats.last30" to "Added in last 30 days",
    "stats.month" to "30 days",
    "stats.daysAgo30" to "29 days ago",
    "stats.today" to "Reviewed today",
    "stats.week" to "Last 7 days",
    "stats.streak" to "Day streak",
    "stats.due" to "Due now",
    "stats.mastery" to "Mastery",
    "stats.last7" to "Added in last 7 days",
    "stats.subjects" to "Subjects",
    "stats.total" to "{n} total",
    "stats.empty" to "No mistakes yet — take a photo!",
    "stats.daysAgo7" to "6 days ago",
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
    "settings.group.general" to "General",
    "settings.group.personalize" to "Personalization",
    "settings.group.notifyBackground" to "🔔 Notifications & background",
    "settings.aiCrop" to "🤖 AI settings",
    "settings.aiCrop.timeout" to "✂️ AI crop timeout",
    "settings.customPrompt" to "✍️ Custom instructions",
    "settings.customPrompt.desc" to "Anything written here is appended to every AI request. For example: give hints only, no full answer / number the steps and state the reasoning / use more concise language … (leave empty to disable)",
    "settings.solveModel" to "🧠 Solve mode",
    "settings.solveModel.desc" to "Flash: fast and cheap, best for daily use; Reasoner: thinks first, more accurate on hard problems but slower and costs more tokens",
    "settings.solveModel.flash" to "Flash (fast / cheap)",
    "settings.solveModel.reasoner" to "Reasoner (slow / accurate)",
    "settings.aiCrop.desc" to "How long to wait for AI auto-crop after opening the crop screen. If it does not return in time, the local algorithm is used.",
    "settings.aiCrop.snapHint" to "Snap points: 500 ms · 1 s · 3 s",
    "settings.lang" to "🌐 Language",
    "settings.theme" to "🎨 App theme",
    "settings.theme.mode" to "Theme mode",
    "settings.theme.color" to "Theme colour",
    "settings.theme.custom" to "Custom colour",
    "settings.theme.hue" to "Hue",
    "settings.theme.sat" to "Saturation",
    "settings.theme.value" to "Brightness",
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
    "nav.learn" to "学習",
    "nav.stats" to "統計",
    "stats.title" to "📊 学習統計",
    "stats.last30" to "直近 30 日の追加",
    "stats.month" to "30 日",
    "stats.daysAgo30" to "29 日前",
    "stats.today" to "今日の復習",
    "stats.week" to "直近 7 日",
    "stats.streak" to "連続日数",
    "stats.due" to "復習待ち",
    "stats.mastery" to "習得度",
    "stats.last7" to "直近 7 日の追加",
    "stats.subjects" to "科目別",
    "stats.total" to "全 {n} 問",
    "stats.empty" to "まだ間違いがありません。撮影してみましょう",
    "stats.daysAgo7" to "6 日前",
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
    "settings.group.general" to "一般",
    "settings.group.personalize" to "パーソナライズ",
    "settings.group.notifyBackground" to "🔔 通知とバックグラウンド",
    "settings.aiCrop" to "🤖 AI 設定",
    "settings.aiCrop.timeout" to "✂️ AI 枠取りの制限時間",
    "settings.customPrompt" to "✍️ カスタム指示",
    "settings.customPrompt.desc" to "ここに書いた内容は毎回の AI 質問に追加されます。例：考え方だけ／手順に番号と根拠／より簡潔に…（空欄なら無効）",
    "settings.solveModel" to "🧠 解答モード",
    "settings.solveModel.desc" to "Flash：速くて安い（普段向け）；Reasoner：先に推論するので難問に強いが遅くトークン消費も多い",
    "settings.solveModel.flash" to "Flash（速い / 安い）",
    "settings.solveModel.reasoner" to "Reasoner（遅い / 高精度）",
    "settings.aiCrop.desc" to "切り抜き画面を開いた後、AI の自動切り抜きを待つ上限。時間内に返らない場合はローカルアルゴリズムで切り抜きます。",
    "settings.aiCrop.snapHint" to "スナップ点：500 ms · 1 s · 3 s",
    "settings.lang" to "🌐 言語設定",
    "settings.theme" to "🎨 アプリテーマ",
    "settings.theme.mode" to "テーマモード",
    "settings.theme.color" to "テーマカラー",
    "settings.theme.custom" to "カスタムカラー",
    "settings.theme.hue" to "色相",
    "settings.theme.sat" to "彩度",
    "settings.theme.value" to "明度",
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
    "nav.learn" to "학습",
    "nav.stats" to "통계",
    "stats.title" to "📊 학습 통계",
    "stats.last30" to "최근 30일 추가",
    "stats.month" to "30일",
    "stats.daysAgo30" to "29일 전",
    "stats.today" to "오늘 복습",
    "stats.week" to "최근 7일",
    "stats.streak" to "연속 일수",
    "stats.due" to "복습 대기",
    "stats.mastery" to "숙달도",
    "stats.last7" to "최근 7일 추가",
    "stats.subjects" to "과목별",
    "stats.total" to "총 {n}문제",
    "stats.empty" to "아직 오답이 없습니다. 사진을 찍어 보세요",
    "stats.daysAgo7" to "6일 전",
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
    "settings.group.general" to "일반",
    "settings.group.personalize" to "개인 설정",
    "settings.group.notifyBackground" to "🔔 알림 및 백그라운드",
    "settings.aiCrop" to "🤖 AI 설정",
    "settings.aiCrop.timeout" to "✂️ AI 자르기 제한 시간",
    "settings.customPrompt" to "✍️ 사용자 지정 지시",
    "settings.customPrompt.desc" to "여기에 쓴 내용은 모든 AI 질문에 추가됩니다. 예: 풀이 방향만/단계 번호와 근거/더 간결하게… (비우면 적용 안 됨)",
    "settings.solveModel" to "🧠 풀이 모드",
    "settings.solveModel.desc" to "Flash: 빠르고 저렴(일상용); Reasoner: 먼저 추론해 어려운 문제에 강하지만 느리고 토큰 소모가 큼",
    "settings.solveModel.flash" to "Flash (빠름 / 저렴)",
    "settings.solveModel.reasoner" to "Reasoner (느림 / 정확)",
    "settings.aiCrop.desc" to "자르기 화면을 연 뒤 AI 자동 자르기를 기다리는 상한. 시간 안에 오지 않으면 로컬 알고리즘으로 자릅니다.",
    "settings.aiCrop.snapHint" to "스냅 지점: 500 ms · 1 s · 3 s",
    "settings.lang" to "🌐 언어 설정",
    "settings.theme" to "🎨 앱 테마",
    "settings.theme.mode" to "테마 모드",
    "settings.theme.color" to "테마 색상",
    "settings.theme.custom" to "사용자 지정 색",
    "settings.theme.hue" to "색상",
    "settings.theme.sat" to "채도",
    "settings.theme.value" to "밝기",
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

    "ask.pageTitle" to "图文提问",
    "ask.addImage" to "🖼 加图",
    "ask.placeholder" to "输入你的问题，可直接用文字提问…",
    "ask.imageDesc" to "附图",
    "ask.send" to "提问 AI",

    "crop.noImage" to "未找到图片，请重新拍照",
    "crop.backToCamera" to "回到拍摄界面",
    "crop.aiAutoCrop" to "🤖 AI 自动框选",
    "crop.prevImage" to "← 上一张",
    "crop.multiPos" to "第 {i}/{n} 张",
    "crop.wholeHoldHint" to "按住 2 秒后松手跳过 · 上滑取消",
    "crop.autoSelected" to "已自动框选题目范围，可拖动微调",
    "crop.aiDetect" to "🤖 AI 识别",
    "crop.aiBusy" to "识别中…",
    "crop.confirm" to "✅ 确认框选并解答",
    "crop.confirmMore" to "✅ 继续上传第二张",
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

    "ask.pageTitle" to "Ask with text/image",
    "ask.addImage" to "🖼 Add image",
    "ask.placeholder" to "Type your question — text only is fine…",
    "ask.imageDesc" to "Attached image",
    "ask.send" to "Ask AI",

    "crop.noImage" to "Image not found, please take the photo again",
    "crop.backToCamera" to "Back to camera",
    "crop.aiAutoCrop" to "🤖 AI auto-crop",
    "crop.prevImage" to "← Previous",
    "crop.multiPos" to "Image {i} of {n}",
    "crop.wholeHoldHint" to "Hold 2s, release to skip · slide up to cancel",
    "crop.autoSelected" to "Question area auto-selected — drag to fine-tune",
    "crop.aiDetect" to "🤖 AI detect",
    "crop.aiBusy" to "Detecting…",
    "crop.confirm" to "✅ Confirm selection & solve",
    "crop.confirmMore" to "✅ Upload next image",
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

    "ask.pageTitle" to "文字/画像で質問",
    "ask.addImage" to "🖼 画像を追加",
    "ask.placeholder" to "質問を入力（文字だけでもOK）…",
    "ask.imageDesc" to "添付画像",
    "ask.send" to "AI に質問",

    "crop.noImage" to "画像が見つかりません。撮り直してください",
    "crop.backToCamera" to "撮影画面へ戻る",
    "crop.aiAutoCrop" to "🤖 AI 自動切り抜き",
    "crop.prevImage" to "← 前の 1 枚",
    "crop.multiPos" to "{n} 枚中 {i} 枚目",
    "crop.wholeHoldHint" to "2 秒長押し→離してスキップ・上スワイプで取消",
    "crop.autoSelected" to "問題範囲を自動選択しました（ドラッグで微調整）",
    "crop.aiDetect" to "🤖 AI 認識",
    "crop.aiBusy" to "認識中…",
    "crop.confirm" to "✅ 範囲を確定して解答",
    "crop.confirmMore" to "✅ 次の画像を追加",
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

    "ask.pageTitle" to "글/이미지 질문",
    "ask.addImage" to "🖼 이미지 추가",
    "ask.placeholder" to "질문을 입력하세요 (텍스트만으로도 가능)…",
    "ask.imageDesc" to "첨부 이미지",
    "ask.send" to "AI에게 질문",

    "crop.noImage" to "이미지를 찾을 수 없습니다. 다시 촬영하세요",
    "crop.backToCamera" to "촬영 화면으로",
    "crop.aiAutoCrop" to "🤖 AI 자동 자르기",
    "crop.prevImage" to "← 이전",
    "crop.multiPos" to "{n}장 중 {i}장",
    "crop.wholeHoldHint" to "2초 누른 뒤 떼면 건너뛰기 · 위로 밀면 취소",
    "crop.autoSelected" to "문제 영역을 자동 선택했습니다 (드래그로 미세 조정)",
    "crop.aiDetect" to "🤖 AI 인식",
    "crop.aiBusy" to "인식 중…",
    "crop.confirm" to "✅ 선택 확정 후 풀이",
    "crop.confirmMore" to "✅ 다음 이미지 추가",
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
    "solve.imagesCount" to "已附 {n} 张图",
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
    "solve.imagesCount" to "{n} image(s) attached",
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
    "solve.imagesCount" to "画像 {n} 枚を添付",
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
    "solve.imagesCount" to "이미지 {n}장 첨부",
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

    // 崩溃日志（v0.6.1.2）：只存本机 + 用户主动导出/清空
    "data.crash" to "🐞 崩溃日志",
    "data.crash.count" to "本机已记录 {n} 份崩溃日志（最多保留 10 份）",
    "data.crash.none" to "暂无崩溃记录",
    "data.crash.export" to "导出崩溃日志",
    "data.crash.export.desc" to "只在崩溃时自动记到本机、不会上传；导出成 txt 后可自行发送以便定位问题",
    "data.crash.exportDone" to "已导出崩溃日志",
    "data.crash.clear" to "清空崩溃日志",
    "data.crash.clearDone" to "已清空崩溃日志",
    "data.crash.failed" to "操作失败：{msg}",
    "data.crash.clear.title" to "确认清空崩溃日志",
    "data.crash.clear.text" to "将删除本机记录的全部崩溃日志，不影响错题等其他数据。",

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

    // Crash logs (v0.6.1.2): stored locally only, exported/cleared by the user
    "data.crash" to "🐞 Crash logs",
    "data.crash.count" to "{n} crash log(s) on this device (last 10 kept)",
    "data.crash.none" to "No crash recorded",
    "data.crash.export" to "Export crash logs",
    "data.crash.export.desc" to "Recorded locally on crash only — never uploaded; export as txt and send it yourself to help diagnose",
    "data.crash.exportDone" to "Crash logs exported",
    "data.crash.clear" to "Clear crash logs",
    "data.crash.clearDone" to "Crash logs cleared",
    "data.crash.failed" to "Failed: {msg}",
    "data.crash.clear.title" to "Clear crash logs?",
    "data.crash.clear.text" to "Deletes all crash logs stored on this device. Other data (problems, etc.) is untouched.",

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

    // クラッシュログ（v0.6.1.2）：端末内のみ保存・ユーザー操作で書き出し/削除
    "data.crash" to "🐞 クラッシュログ",
    "data.crash.count" to "この端末に {n} 件のクラッシュログがあります（最新 10 件まで保持）",
    "data.crash.none" to "クラッシュの記録はありません",
    "data.crash.export" to "クラッシュログを書き出す",
    "data.crash.export.desc" to "クラッシュ時に端末内へ記録するだけで、送信はしません。txt で書き出してご自身で送付いただけます",
    "data.crash.exportDone" to "クラッシュログを書き出しました",
    "data.crash.clear" to "クラッシュログを削除",
    "data.crash.clearDone" to "クラッシュログを削除しました",
    "data.crash.failed" to "失敗しました：{msg}",
    "data.crash.clear.title" to "クラッシュログの削除",
    "data.crash.clear.text" to "端末内のクラッシュログをすべて削除します。問題などの他のデータには影響しません。",

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

    // 크래시 로그(v0.6.1.2): 기기 내에만 저장, 사용자가 직접 내보내기/삭제
    "data.crash" to "🐞 크래시 로그",
    "data.crash.count" to "이 기기에 크래시 로그 {n}개가 있습니다(최근 10개 보관)",
    "data.crash.none" to "크래시 기록이 없습니다",
    "data.crash.export" to "크래시 로그 내보내기",
    "data.crash.export.desc" to "크래시 시 기기에만 기록하며 전송하지 않습니다. txt로 내보내 직접 보내주시면 문제 파악에 도움이 됩니다",
    "data.crash.exportDone" to "크래시 로그를 내보냈습니다",
    "data.crash.clear" to "크래시 로그 삭제",
    "data.crash.clearDone" to "크래시 로그를 삭제했습니다",
    "data.crash.failed" to "실패: {msg}",
    "data.crash.clear.title" to "크래시 로그 삭제",
    "data.crash.clear.text" to "기기에 기록된 모든 크래시 로그를 삭제합니다. 오답 등 다른 데이터에는 영향이 없습니다.",

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
    // 思考块（WebView 渲染）的文案：由 ConversationWebView 以 JSON 注入 conversation_render.html
    "think.title" to "💭 思考过程",
    "think.state.thinking" to "（思考中…）",
    "think.state.collapse" to "（点击折叠）",
    "think.state.expand" to "（点击展开）",
    "think.fold" to "折叠思考区",
    "think.unfold" to "展开思考区",
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
    "think.title" to "💭 Thinking",
    "think.state.thinking" to " (thinking…)",
    "think.state.collapse" to " (tap to collapse)",
    "think.state.expand" to " (tap to expand)",
    "think.fold" to "Collapse thinking",
    "think.unfold" to "Expand thinking",
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
    "think.title" to "💭 思考プロセス",
    "think.state.thinking" to "（思考中…）",
    "think.state.collapse" to "（タップで折りたたむ）",
    "think.state.expand" to "（タップで展開）",
    "think.fold" to "思考を折りたたむ",
    "think.unfold" to "思考を展開する",
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
    "think.title" to "💭 사고 과정",
    "think.state.thinking" to " (생각 중…)",
    "think.state.collapse" to " (탭하여 접기)",
    "think.state.expand" to " (탭하여 펼치기)",
    "think.fold" to "사고 과정 접기",
    "think.unfold" to "사고 과정 펼치기",
    "review.titleProgress" to "복습 {i}/{n}",
    "review.nextQuestion" to "다음 문제"
)

// ===========================================================================
// B10：设置结构调整（AI 管理 / 应用更新）+ 关于页功能栏 + 应用更新页（0.6.1）
// ===========================================================================
private val B10_ZH = mapOf(
    "settings.aiManage" to "🤖 AI 管理",
    "settings.update" to "⬆️ 应用更新",
    "about.features" to "📋 功能",
    "about.repoLabel" to "项目主页：",
    "about.features.body" to (
        "· 📷 拍照搜题：自动预框选 + 手动框选，AI 识别并分步解答\n" +
        "· ✏️ 图文提问：文字 + 最多 3 张图直接提问\n" +
        "· ✍️ 批改题目：拍题目 + 手写作答，AI 判断正误并针对性讲解\n" +
        "· 🧠 AI 思考流：浅灰小字展示思考过程，可折叠、流式跟随\n" +
        "· 📐 KaTeX 公式排版：积分 / 分式 / 矩阵等正确渲染\n" +
        "· 💬 多轮追问：可带图追问，支持中止与继续生成\n" +
        "· 🎯 练同类题：AI 出题并给出答案\n" +
        "· 📚 错题本：两列瀑布流、全文搜索、科目分类 + 知识点标签\n" +
        "· 🗂 自动归类：AI 判定科目与知识点，打开旧题自动补齐\n" +
        "· 📖 艾宾浩斯复习：0/1/2/4/7/15/30 天排期，熟悉 / 模糊 / 忘记\n" +
        "· 📊 学习统计：复习量、连续天数、掌握度、科目分布\n" +
        "· 🔔 复习提醒：每日定时通知，点击直达复习\n" +
        "· 🎨 应用主题：浅色 / 深色 / 跟随系统 + 8 套配色与自定义取色\n" +
        "· 🌐 多语言界面：简体 / 繁體 / English / 日本語 / 한국어\n" +
        "· 🔑 API Key 应用内填写：Android Keystore 加密，仅存本机\n" +
        "· 💾 数据管理：导出 / 导入 JSON 备份、清空数据\n" +
        "· ⬆️ 应用更新：应用内检查、下载并校验（SHA-256 + 签名）\n" +
        "· 🔒 隐私：所有数据只存本机，不上传服务器"
        ),
    "update.current" to "当前版本",
    "update.check" to "检查更新",
    "update.checking" to "正在检查…",
    "update.upToDate" to "已是最新版本",
    "update.found" to "发现新版本 {v}",
    "update.sameVersion" to "发现同一版本的新构建：{name}",
    "update.download" to "下载更新",
    "update.downloading" to "下载中 {p}%",
    "update.verifying" to "校验中…",
    "update.verified" to "校验通过（SHA-256 与签名均匹配）",
    "update.verifiedSigner" to "校验通过（签名证书匹配）",
    "update.verifyFailed" to "校验失败，已删除下载的文件",
    "update.install" to "安装更新",
    "update.needPermission" to "需要先允许本应用「安装未知应用」",
    "update.grant" to "去允许",
    "update.hint" to "更新包取自 GitHub 仓库，有时网络可能不稳定。",
    "update.openBrowser" to "在浏览器打开发布页",
    "update.netError" to "检查失败：网络不可用或无法访问 GitHub",
    // v0.6.1.3 更新页增强：新版本说明 / 跳过此版本 / 上次检查时间 / 失败重试
    "update.notes" to "本次更新内容",
    "update.lastCheck" to "上次检查：{t}",
    "update.neverChecked" to "尚未检查过更新",
    "update.skip" to "跳过此版本",
    "update.skipped" to "已跳过 v{v}（仍可手动下载）",
    "update.unskip" to "取消跳过",
    "update.retry" to "重试",
    "update.redownload" to "重新下载"
)

private val B10_EN = mapOf(
    "settings.aiManage" to "🤖 AI management",
    "settings.update" to "⬆️ App update",
    "about.features" to "📋 Features",
    "about.repoLabel" to "Project: ",
    "about.features.body" to (
        "· 📷 Photo search: automatic + manual crop, AI recognises and solves step by step\n" +
        "· ✏️ Ask with images: text plus up to 3 photos\n" +
        "· ✍️ Grading: photo of the question + your handwritten answer, AI marks and explains\n" +
        "· 🧠 Thinking stream: shows the model's reasoning in small grey text, collapsible and streaming\n" +
        "· 📐 KaTeX rendering: integrals, fractions, matrices and more\n" +
        "· 💬 Follow-up questions with images; stop and continue generation\n" +
        "· 🎯 Similar-question practice: the AI generates a question and its answer\n" +
        "· 📚 Mistake notebook: two-column grid, full-text search, subjects + knowledge tags\n" +
        "· 🗂 Auto categorising: the AI assigns subject and tags; older entries are filled in on open\n" +
        "· 📖 Spaced repetition: 0/1/2/4/7/15/30-day schedule with familiar / vague / forgot\n" +
        "· 📊 Study stats: review counts, streaks, mastery, subject breakdown\n" +
        "· 🔔 Review reminders: a daily notification that opens review directly\n" +
        "· 🎨 Themes: light / dark / system, 8 palettes and a custom colour picker\n" +
        "· 🌐 Multi-language UI: 简体 / 繁體 / English / 日本語 / 한국어\n" +
        "· 🔑 API key entered in-app, encrypted with Android Keystore, never uploaded\n" +
        "· 💾 Data management: export / import JSON backup, wipe data\n" +
        "· ⬆️ App update: check, download and verify in-app (SHA-256 + signature)\n" +
        "· 🔒 Privacy: all data stays on your device"
        ),
    "update.current" to "Current version",
    "update.check" to "Check for updates",
    "update.checking" to "Checking…",
    "update.upToDate" to "You're up to date",
    "update.found" to "New version {v} is available",
    "update.sameVersion" to "A new build of the same version is available: {name}",
    "update.download" to "Download update",
    "update.downloading" to "Downloading {p}%",
    "update.verifying" to "Verifying…",
    "update.verified" to "Verified (SHA-256 and signature match)",
    "update.verifiedSigner" to "Verified (signing certificate matches)",
    "update.verifyFailed" to "Verification failed; the downloaded file was deleted",
    "update.install" to "Install update",
    "update.needPermission" to "Please allow this app to install unknown apps first",
    "update.grant" to "Allow",
    "update.hint" to "Update packages come from the GitHub repository; the network may be unstable at times.",
    "update.openBrowser" to "Open the releases page in a browser",
    "update.netError" to "Check failed: network unavailable or GitHub unreachable",
    // v0.6.1.3 update page: release notes / skip this version / last check / retry
    "update.notes" to "What's new in this version",
    "update.lastCheck" to "Last checked: {t}",
    "update.neverChecked" to "Never checked for updates",
    "update.skip" to "Skip this version",
    "update.skipped" to "v{v} skipped (you can still download it manually)",
    "update.unskip" to "Don't skip",
    "update.retry" to "Retry",
    "update.redownload" to "Download again"
)

private val B10_JA = mapOf(
    "settings.aiManage" to "🤖 AI 管理",
    "settings.update" to "⬆️ アプリ更新",
    "about.features" to "📋 機能",
    "about.repoLabel" to "プロジェクト：",
    "about.features.body" to (
        "· 📷 撮影検索：自動＋手動の枠取り、AI が認識して段階的に解答\n" +
        "· ✏️ 画像付き質問：テキスト＋最大 3 枚の写真\n" +
        "· ✍️ 添削：問題＋手書き解答の写真から正誤と解説\n" +
        "· 🧠 思考ストリーム：薄いグレーの小文字で思考過程を表示（折りたたみ可・逐次表示）\n" +
        "· 📐 KaTeX による数式組版：積分・分数・行列など\n" +
        "· 💬 画像付きの追加質問／生成の中止と再開\n" +
        "· 🎯 類似問題演習：AI が出題し解答も提示\n" +
        "· 📚 誤答ノート：2 列レイアウト・全文検索・科目＋知識タグ\n" +
        "· 🗂 自動分類：AI が科目とタグを判定し、古い問題は開いた時に補完\n" +
        "· 📖 間隔反復：0/1/2/4/7/15/30 日のスケジュール（熟悉 / 模糊 / 忘记）\n" +
        "· 📊 学習統計：復習数・連続日数・習熟度・科目分布\n" +
        "· 🔔 復習リマインダー：毎日通知、タップで復習へ\n" +
        "· 🎨 テーマ：ライト / ダーク / システム＋8 色とカスタムカラー\n" +
        "· 🌐 多言語 UI：简体 / 繁體 / English / 日本語 / 한국어\n" +
        "· 🔑 API キーはアプリ内で入力し Android Keystore に暗号化保存\n" +
        "· 💾 データ管理：JSON のエクスポート / インポート、全消去\n" +
        "· ⬆️ アプリ更新：アプリ内で確認・ダウンロード・検証（SHA-256＋署名）\n" +
        "· 🔒 プライバシー：データは端末内のみ"
        ),
    "update.current" to "現在のバージョン",
    "update.check" to "更新を確認",
    "update.checking" to "確認中…",
    "update.upToDate" to "最新版です",
    "update.found" to "新しいバージョン {v} があります",
    "update.sameVersion" to "同一バージョンの新しいビルドがあります：{name}",
    "update.download" to "更新をダウンロード",
    "update.downloading" to "ダウンロード中 {p}%",
    "update.verifying" to "検証中…",
    "update.verified" to "検証済み（SHA-256 と署名が一致）",
    "update.verifiedSigner" to "検証済み（署名証明書が一致）",
    "update.verifyFailed" to "検証に失敗しました。ダウンロードしたファイルは削除しました",
    "update.install" to "更新をインストール",
    "update.needPermission" to "先に「提供元不明のアプリ」のインストールを許可してください",
    "update.grant" to "許可する",
    "update.hint" to "更新パッケージは GitHub リポジトリから取得します。ネットワークが不安定なことがあります。",
    "update.openBrowser" to "リリースページをブラウザで開く",
    "update.netError" to "確認に失敗しました：ネットワーク不可、または GitHub に接続できません",
    // v0.6.1.3 更新ページ拡張：更新内容 / このバージョンをスキップ / 前回確認 / 再試行
    "update.notes" to "今回の更新内容",
    "update.lastCheck" to "前回の確認：{t}",
    "update.neverChecked" to "まだ更新を確認していません",
    "update.skip" to "このバージョンをスキップ",
    "update.skipped" to "v{v} をスキップしました（手動でのダウンロードは可能）",
    "update.unskip" to "スキップを解除",
    "update.retry" to "再試行",
    "update.redownload" to "再ダウンロード"
)

private val B10_KO = mapOf(
    "settings.aiManage" to "🤖 AI 관리",
    "settings.update" to "⬆️ 앱 업데이트",
    "about.features" to "📋 기능",
    "about.repoLabel" to "프로젝트: ",
    "about.features.body" to (
        "· 📷 사진 검색: 자동·수동 자르기 후 AI가 인식해 단계별 풀이\n" +
        "· ✏️ 이미지 질문: 텍스트 + 최대 3장의 사진\n" +
        "· ✍️ 첨삭: 문제 + 손글씨 답안 사진으로 정오 판단과 설명\n" +
        "· 🧠 사고 과정: 연한 회색 작은 글씨로 추론 과정 표시(접기 가능, 실시간)\n" +
        "· 📐 KaTeX 수식 조판: 적분·분수·행렬 등\n" +
        "· 💬 이미지 첨부 추가 질문 / 생성 중지와 재개\n" +
        "· 🎯 유사 문제 연습: AI가 출제하고 해답 제시\n" +
        "· 📚 오답노트: 2열 배치·전체 검색·과목 + 지식 태그\n" +
        "· 🗂 자동 분류: AI가 과목과 태그를 판정, 오래된 문제는 열 때 보완\n" +
        "· 📖 간격 반복: 0/1/2/4/7/15/30일 일정(익숙 / 애매 / 모름)\n" +
        "· 📊 학습 통계: 복습 수·연속 일수·숙련도·과목 분포\n" +
        "· 🔔 복습 알림: 매일 알림, 탭하면 복습으로 이동\n" +
        "· 🎨 테마: 라이트 / 다크 / 시스템 + 8가지 색상과 사용자 지정 색\n" +
        "· 🌐 다국어 UI: 简体 / 繁體 / English / 日本語 / 한국어\n" +
        "· 🔑 API 키는 앱 내 입력, Android Keystore로 암호화 저장\n" +
        "· 💾 데이터 관리: JSON 내보내기 / 가져오기, 전체 삭제\n" +
        "· ⬆️ 앱 업데이트: 앱 내 확인·다운로드·검증(SHA-256 + 서명)\n" +
        "· 🔒 개인정보: 모든 데이터는 기기 안에만"
        ),
    "update.current" to "현재 버전",
    "update.check" to "업데이트 확인",
    "update.checking" to "확인 중…",
    "update.upToDate" to "최신 버전입니다",
    "update.found" to "새 버전 {v}이(가) 있습니다",
    "update.sameVersion" to "같은 버전의 새 빌드가 있습니다: {name}",
    "update.download" to "업데이트 다운로드",
    "update.downloading" to "다운로드 중 {p}%",
    "update.verifying" to "검증 중…",
    "update.verified" to "검증됨(SHA-256 및 서명 일치)",
    "update.verifiedSigner" to "검증됨(서명 인증서 일치)",
    "update.verifyFailed" to "검증 실패: 내려받은 파일을 삭제했습니다",
    "update.install" to "업데이트 설치",
    "update.needPermission" to "먼저 이 앱의 '알 수 없는 앱 설치'를 허용해 주세요",
    "update.grant" to "허용",
    "update.hint" to "업데이트 패키지는 GitHub 저장소에서 받습니다. 네트워크가 불안정할 수 있습니다.",
    "update.openBrowser" to "브라우저에서 릴리스 페이지 열기",
    "update.netError" to "확인 실패: 네트워크 불가 또는 GitHub 접속 실패",
    // v0.6.1.3 업데이트 페이지 확장: 업데이트 내용 / 이 버전 건너뛰기 / 마지막 확인 / 다시 시도
    "update.notes" to "이번 업데이트 내용",
    "update.lastCheck" to "마지막 확인: {t}",
    "update.neverChecked" to "아직 업데이트를 확인하지 않았습니다",
    "update.skip" to "이 버전 건너뛰기",
    "update.skipped" to "v{v} 건너뜀(수동 다운로드는 가능)",
    "update.unskip" to "건너뛰기 해제",
    "update.retry" to "다시 시도",
    "update.redownload" to "다시 다운로드"
)

// ===========================================================================
// 合并（每批新增后在这里追加对应的 Bn_xx）
// ===========================================================================
internal val ZH: Map<String, String> = B0_ZH + B1_ZH + B2_ZH + B3_ZH + B4_ZH + B5_ZH + B6_ZH + B7_ZH + B8_ZH + B9_ZH + B10_ZH
internal val EN: Map<String, String> = B0_EN + B1_EN + B2_EN + B3_EN + B4_EN + B5_EN + B6_EN + B7_EN + B8_EN + B9_EN + B10_EN
internal val JA: Map<String, String> = B0_JA + B1_JA + B2_JA + B3_JA + B4_JA + B5_JA + B6_JA + B7_JA + B8_JA + B9_JA + B10_JA
internal val KO: Map<String, String> = B0_KO + B1_KO + B2_KO + B3_KO + B4_KO + B5_KO + B6_KO + B7_KO + B8_KO + B9_KO + B10_KO
