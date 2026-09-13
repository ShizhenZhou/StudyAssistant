package com.zsz.studyassistant.ui

/**
 * 界面文案表（简体中文为基准，其余语言按 key 对应）。
 *
 * 组织方式：每批新增一个分区（B0_xx、B1_xx …），文件末尾把各分区**合并**成
 * ZH / EN / JA / … 供 Strings 使用。这样每批只动一处、也便于逐批复核。
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
    "home.grade" to "✏️  批改题目",
    "home.notebook" to "📚  错题本",
    "home.review" to "📖  复习",
    "home.key.title" to "配置 DeepSeek API Key",
    "home.key.hint" to "请输入你的 DeepSeek API Key（sk- 开头，加密保存在本机）",
    "home.key.save" to "保存",
    "home.key.later" to "稍后",

    "settings.title" to "设置",
    "settings.api" to "🔑 DeepSeek API Key",
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
    "home.grade" to "✏️  Grade my work",
    "home.notebook" to "📚  Notebook",
    "home.review" to "📖  Review",
    "home.key.title" to "Set up DeepSeek API Key",
    "home.key.hint" to "Paste your DeepSeek API Key (starts with sk-, encrypted and stored on this device)",
    "home.key.save" to "Save",
    "home.key.later" to "Later",

    "settings.title" to "Settings",
    "settings.api" to "🔑 DeepSeek API Key",
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
    "home.grade" to "✏️  添削",
    "home.notebook" to "📚  間違いノート",
    "home.review" to "📖  復習",
    "home.key.title" to "DeepSeek API Key の設定",
    "home.key.hint" to "DeepSeek API Key を入力してください（sk- で始まる／端末内に暗号化して保存）",
    "home.key.save" to "保存",
    "home.key.later" to "後で",

    "settings.title" to "設定",
    "settings.api" to "🔑 DeepSeek API Key",
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
    "home.grade" to "✏️  채점",
    "home.notebook" to "📚  오답 노트",
    "home.review" to "📖  복습",
    "home.key.title" to "DeepSeek API Key 설정",
    "home.key.hint" to "DeepSeek API Key를 입력하세요 (sk- 로 시작, 기기에 암호화되어 저장됩니다)",
    "home.key.save" to "저장",
    "home.key.later" to "나중에",

    "settings.title" to "설정",
    "settings.api" to "🔑 DeepSeek API Key",
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

private val B0_DE = mapOf(
    "nav.home" to "Start",
    "nav.settings" to "Einstellungen",

    "home.subtitle" to "Foto-Suche · KI-Lösungen · Fehlernotizbuch",
    "home.camera" to "📷  Per Foto fragen",
    "home.grade" to "✏️  Korrigieren",
    "home.notebook" to "📚  Notizbuch",
    "home.review" to "📖  Wiederholen",
    "home.key.title" to "DeepSeek API Key einrichten",
    "home.key.hint" to "DeepSeek API Key einfügen (beginnt mit sk-, wird verschlüsselt auf dem Gerät gespeichert)",
    "home.key.save" to "Speichern",
    "home.key.later" to "Später",

    "settings.title" to "Einstellungen",
    "settings.api" to "🔑 DeepSeek API Key",
    "settings.lang" to "🌐 Sprache",
    "settings.theme" to "🎨 Design",
    "settings.notify" to "🔔 Wiederholungs-Erinnerung",
    "settings.background" to "🔋 Hintergrundbetrieb",
    "settings.changelog" to "📝 Neuerungen",
    "settings.about" to "ℹ️ Über",

    "lang.title" to "🌐 Sprache",
    "lang.ui" to "App-Sprache",
    "lang.ui.desc" to "Sprache der App-Oberfläche (sofort wirksam, kein Neustart)",
    "lang.ai" to "Sprache der KI-Antworten",
    "lang.ai.desc" to "Sprache, in der die KI antwortet. Gilt für Fotofragen, direkte Fragen, Rückfragen, Korrektur und ähnliche Aufgaben. Formeln bleiben in LaTeX.",
    "lang.followSystem" to "Systemeinstellung folgen",
    "lang.followQuestion" to "Der Aufgabe folgen"
)

private val B0_FR = mapOf(
    "nav.home" to "Accueil",
    "nav.settings" to "Réglages",

    "home.subtitle" to "Recherche par photo · Solutions IA · Carnet d'erreurs",
    "home.camera" to "📷  Demander par photo",
    "home.grade" to "✏️  Corriger",
    "home.notebook" to "📚  Carnet",
    "home.review" to "📖  Révision",
    "home.key.title" to "Configurer la clé API DeepSeek",
    "home.key.hint" to "Collez votre clé API DeepSeek (commence par sk-, chiffrée et stockée sur l'appareil)",
    "home.key.save" to "Enregistrer",
    "home.key.later" to "Plus tard",

    "settings.title" to "Réglages",
    "settings.api" to "🔑 DeepSeek API Key",
    "settings.lang" to "🌐 Langue",
    "settings.theme" to "🎨 Thème",
    "settings.notify" to "🔔 Rappel de révision",
    "settings.background" to "🔋 Exécution en arrière-plan",
    "settings.changelog" to "📝 Nouveautés",
    "settings.about" to "ℹ️ À propos",

    "lang.title" to "🌐 Langue",
    "lang.ui" to "Langue de l'application",
    "lang.ui.desc" to "Langue de l'interface (effet immédiat, sans redémarrage)",
    "lang.ai" to "Langue des réponses de l'IA",
    "lang.ai.desc" to "Langue dans laquelle l'IA répond : questions par photo, questions directes, relances, correction et exercices similaires. Les formules restent en LaTeX.",
    "lang.followSystem" to "Suivre le système",
    "lang.followQuestion" to "Suivre l'énoncé"
)

private val B0_ES = mapOf(
    "nav.home" to "Inicio",
    "nav.settings" to "Ajustes",

    "home.subtitle" to "Buscar por foto · Soluciones IA · Cuaderno de errores",
    "home.camera" to "📷  Preguntar por foto",
    "home.grade" to "✏️  Corregir",
    "home.notebook" to "📚  Cuaderno",
    "home.review" to "📖  Repaso",
    "home.key.title" to "Configurar la clave API de DeepSeek",
    "home.key.hint" to "Pega tu clave API de DeepSeek (empieza por sk-, se cifra y se guarda en el dispositivo)",
    "home.key.save" to "Guardar",
    "home.key.later" to "Más tarde",

    "settings.title" to "Ajustes",
    "settings.api" to "🔑 DeepSeek API Key",
    "settings.lang" to "🌐 Idioma",
    "settings.theme" to "🎨 Tema",
    "settings.notify" to "🔔 Recordatorio de repaso",
    "settings.background" to "🔋 Ejecución en segundo plano",
    "settings.changelog" to "📝 Novedades",
    "settings.about" to "ℹ️ Acerca de",

    "lang.title" to "🌐 Idioma",
    "lang.ui" to "Idioma de la app",
    "lang.ui.desc" to "Idioma de la interfaz (se aplica al instante, sin reiniciar)",
    "lang.ai" to "Idioma de las respuestas de la IA",
    "lang.ai.desc" to "Idioma en que responde la IA: preguntas por foto, directas, repreguntas, corrección y problemas similares. Las fórmulas siguen en LaTeX.",
    "lang.followSystem" to "Seguir el sistema",
    "lang.followQuestion" to "Seguir el enunciado"
)

private val B0_RU = mapOf(
    "nav.home" to "Главная",
    "nav.settings" to "Настройки",

    "home.subtitle" to "Поиск по фото · Решения ИИ · Тетрадь ошибок",
    "home.camera" to "📷  Спросить по фото",
    "home.grade" to "✏️  Проверить",
    "home.notebook" to "📚  Тетрадь",
    "home.review" to "📖  Повторение",
    "home.key.title" to "Настройка ключа DeepSeek API",
    "home.key.hint" to "Вставьте ключ DeepSeek API (начинается с sk-, шифруется и хранится на устройстве)",
    "home.key.save" to "Сохранить",
    "home.key.later" to "Позже",

    "settings.title" to "Настройки",
    "settings.api" to "🔑 DeepSeek API Key",
    "settings.lang" to "🌐 Язык",
    "settings.theme" to "🎨 Тема",
    "settings.notify" to "🔔 Напоминание о повторении",
    "settings.background" to "🔋 Работа в фоне",
    "settings.changelog" to "📝 Что нового",
    "settings.about" to "ℹ️ О приложении",

    "lang.title" to "🌐 Язык",
    "lang.ui" to "Язык интерфейса",
    "lang.ui.desc" to "Язык интерфейса приложения (применяется сразу, без перезапуска)",
    "lang.ai" to "Язык ответов ИИ",
    "lang.ai.desc" to "Язык, на котором отвечает ИИ: вопросы по фото, прямые вопросы, уточнения, проверка и похожие задачи. Формулы остаются в LaTeX.",
    "lang.followSystem" to "Как в системе",
    "lang.followQuestion" to "Как в задаче"
)

// ===========================================================================
// 合并（每批新增后在这里追加对应的 Bn_xx）
// ===========================================================================
internal val ZH: Map<String, String> = B0_ZH
internal val EN: Map<String, String> = B0_EN
internal val JA: Map<String, String> = B0_JA
internal val KO: Map<String, String> = B0_KO
internal val DE: Map<String, String> = B0_DE
internal val FR: Map<String, String> = B0_FR
internal val ES: Map<String, String> = B0_ES
internal val RU: Map<String, String> = B0_RU
