package com.zsz.studyassistant.ui

/**
 * 版本更新内容（与项目 README 的「版本记录」保持一致）。
 * 支持 4 种语言；繁體中文由简体经 s2t() 自动转换（见 L10n.kt 的映射表）。
 */
internal val CHANGELOG_ZH: String = """
v0.5.0
· 设置重构：新增「💾 数据管理」；「关于」改为二级菜单（上面应用信息，下面更新内容）
· 数据管理：**导出备份**（单个 JSON，含题目图片与完整会话）/ **导入备份**（同名分类标签复用、题干相同自动跳过，不会覆盖现有数据）/ **清空所有数据**（二次确认）
· 「🔑 API 管理」：Key 设置 + **累计用量统计**（调用次数、输入/输出 tokens，可重置）；输入框下标注「API Key 一律加密保存在本地」
· 复习默认**折叠解答**：先自己回忆，再点「看解答」展开；顶部显示「第 i/n 题」进度
· 错题本新增**全文搜索**：按题干（AI 转译文本）与解答检索
· 直接提问与追问新增**公式快捷输入条**：∫ ∑ √ π 等符号 + LaTeX 模板（分式/上下标/积分上下限等）
· 重做模式已预留接口（复习时手写重做 → 复用批改 → 自动更新掌握度），后续版本接入
· 对话（解题/批改/同类题）里的图片**点击可全屏放大**：双指缩放、拖动，✕ 或返回键关闭
· 「清空所有数据」需**手动输入确认语**后才可执行，确认按钮为红色；「API Key 加密保存」提示改为绿色字

v0.5.0_beta
· 新增「应用语言」（界面语言）：设置 → 🌐 语言设置，可选 跟随系统 / 简体中文 / 繁體中文 / English / 日本語 / 한국어，切换立即生效、无需重启
· 界面文案全面多语言：主页、拍题、直接提问、解题、批改、错题本、复习、设置、通知等
· 「AI 生成语言」与「应用语言」现为两项独立设置：前者决定 AI 用什么语言作答，后者决定 App 界面文字

v0.4.9
· 拍照题的题目气泡只显示原图：不再显示 AI 转译的题干文字（题干仍保存，错题本/搜索/编辑不受影响；旧会话同样只显示原图）；直接提问仍显示你自己写的问题
· 设置新增二级菜单「🌐 语言设置」→「AI 生成语言」：跟随系统 / 跟随题目 / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Français / Español / Русский
· 语言设置覆盖拍题解答、直接提问、追问、批改、同类题（数学公式仍是 LaTeX）

v0.4.8
· 题目/首次提问统一显示为浅绿色用户气泡（含原图/附图）：单张拍题、两张拍题、直接提问、批改、错题本、复习界面全部一致
· 复习界面改为单列：整行宽度、题图更大更清楚
· R8 混淆 + 资源压缩转为常规（release 变体）：APK 约 4.4MB（原约 30MB），保留旧会话兼容

v0.4.7.5（临时版本）
· 开启 R8 代码裁剪/混淆 + 资源压缩（仅 release），APK 体积大幅缩小；保留旧会话兼容
· 设置页新增二级菜单「📝 更新内容」

v0.4.7
· 直接提问会在对话里显示「我的提问 + 附图」（与拍题后的问答一致）
· 性能：错题本/复习列表图片改为缓存 + 降采样解码（滚动更流畅、内存更省）
· 体积：删除 KaTeX 冗余 .woff（保留 woff2）；预览工具改为仅 debug 依赖
· 拍照搜题新增「单张 / 两张」拍摄模式：两张可拍两张作为同一道题一起解答
· 更多效率优化：API Key 内存缓存；复习页 Flow 缓存；时间格式化器复用；对话消息列表缓存；WebView 内容未变时跳过重渲染；筛选结果缓存；分类/标签名预缓存

v0.4.6
· 「关于」不再做二级菜单，直接显示在设置主页
· 生成答案的通知点击可回到正在生成的页面

v0.4.5
· 设置页改为二级菜单：主页为入口列表（API Key / 应用主题 / 复习提醒 / 后台运行 / 关于），点进去再做具体设置

v0.4.4
· 存题选标签改为下拉菜单（纵向可滑动，保留 AI 自动预选）
· 错题本标签筛选改为选择框「按标签搜索」，点击才弹出下拉多选

v0.4.3
· 模型升级到 DeepSeek-V4.1-Flash（deepseek-flash，原生多模态），视觉与文本统一
· 修复：解题页标题「解题」被右侧按钮挤压成省略号（缩小按钮间距/字号 + 标题保留最小宽度）

v0.4.2
· 点击复习提醒通知直接打开「复习」界面
· 设置页「一键开启自启动」（适配荣耀/华为/小米/OPPO/vivo）
· 拍题界面右上角「✏️ 直接提问」：可用文字 + 1~3 张图直接提问
· 通知时间为系统上下滑动选择

v0.4.1
· 复习完自动跳下一题（同科目优先），今日全部复习完才退出并提示
· 练同类题：AI 出一道同类题（先只显示题目，可互动），点「查看答案」才显示答案
· 复习提醒通知：开关 + 提醒时间（每天一次）
· 通知权限申请；开机自动恢复提醒

v0.4.0
· 复习功能：主页新增「📖 复习」入口
· 艾宾浩斯自动排期：新存错题按 0/1/2/4/7/15/30 天间隔调度（新增 review 表，DB v7）
· 复习界面「今天 / 本周」可选，按科目→知识点分组、两列瀑布流
· 点进错题底部变为 熟悉 / 模糊 / 忘记 三按钮，改变下次出现时机
· 近似题接口预留

v0.3.9
· 知识点标签（tag）：与科目分类解耦、全局不分科、多对多
· AI 自动打 tag（≤5，优先复用已有标签），存题/详情页可改
· 错题本顶部标签多选筛选，卡片显示标签
· 数据库 v5→v6（tags + question_tags 表）

v0.3.8
· 修复：对话正文回归单个 WebView 稳定滚动，错题本点进后上下滑动顺畅
· 修复：错题时间戳记录提问时时间，点进去退出不再刷新
· 版本约定：自定义数据文件头 4 字节记版本号，便于未来迁移
· 密钥策略：源码/仓库不含任何 key

v0.3.7
· 批量改分类 / 批量删除：错题本长按进入多选
· 批量分类：已有 / 新建 / 暂不分类
· 批量删除：彻底删除（弹确认框）
· 多选卡片显示勾选标记 + 选中绿框

v0.3.6
· 错题分类：存题时选择「暂不分类 / 已有分类 / 新建分类」，AI 自动推测科目
· AI 优先归入已有分类，避免重复新建
· 错题本顶部分类筛选；详情页可改分类
· 错题本改为两列瀑布流；分类栏增加「未分类」
· 数据库 v4→v5（categories 表）

v0.3.5
· 追问可带图（1~3 张），AI 同时看图与文字作答
· 输入栏样式：圆角输入框 + 胶囊「发送」按钮
· 追问消息气泡显示附图缩略图；附图随会话保存
· 移除「拍下一题」按钮；解题页返回直达拍题界面
· 拍题/批改相机支持点击对焦 + 对焦框动画

v0.3.4
· 批改题目：单张/两张拍摄，AI 判断正误、指出错误步骤并讲解
· 应用图标字母改为 SA
· 错题本删除改为软删除（可「恢复」）
· 追问输入框键盘避让；拍题图库按钮与快门齐平

v0.3.3
· 应用图标（白底 + 蓝书）；桌面名称 Study Assistant
· 存错题本保存完整对话会话，点击错题可继续问答（续答）
· 框选红色加粗、四角可拖拽；APK 命名规范

v0.3.2
· 底部双选项卡：主页 / 设置
· 应用主题：浅色 / 深色 / 跟随系统
· Key 设置移入设置页；关于页标注作者与版本
· 答案滚动修复；解答改聊天气泡；首次打开自动弹填 Key 对话框

v0.3.1
· 答案区支持 Markdown 排版
· 长答案滚动修复；宽公式自适应 + 横向滚动
· 拍题界面加「重新拍摄」；问答界面加「拍下一题」
· 拍题界面「相册搜题」；解答页顶部可折叠原图
· 错题本显示框选原图，可查看 LaTeX 解答

v0.3.0
· 拍题框选：拍照后框选题目区域，或整张图识别
· 重新生成 + 接续对话（多轮问答）
· API Key 应用内自填（Android Keystore 加密存储）
· KaTeX 公式渲染

v0.2（早期版本）
· 改用 DeepSeek 处理图像：图片直接发给 DeepSeek 视觉模型识别并解答，不再使用百度 OCR

v0.1（项目建立）
· 项目搭建：百度智能云 OCR 识别图像 + DeepSeek 解题，拍照搜题、AI 解答、错题整理雏形
""".trimIndent()

internal val CHANGELOG_EN: String = """
v0.5.0
· Settings reorganized: new "💾 Data" page; "About" is now a subpage (app info on top, what's new below)
· Data management: export a backup (single JSON with images and full conversations) / import a backup (same-name categories and tags are reused, identical questions are skipped — nothing is overwritten) / erase all data (with confirmation)
· "🔑 API" now holds both the key settings and cumulative usage stats (call count, input/output tokens, resettable), with the note "API Key is always stored encrypted on this device"
· Review now hides the answer by default: recall first, then tap "Show answer"; a "i / n" progress line is shown
· Notebook gained full-text search over the transcribed question and the answer
· Direct questions and follow-ups gained a formula bar: ∫ ∑ √ π symbols plus LaTeX templates (fractions, sub/superscripts, integral limits…)
· Redo mode has a reserved interface (hand-write your solution → reuse grading → auto-update mastery); UI comes in a later version
· Images in conversations (solve / grading / similar problems) can be **tapped to view full screen**: pinch to zoom, drag to pan, close with ✕ or Back
· "Erase all data" now requires **typing a confirmation phrase** and the confirm button is red; the "API Key stored encrypted" note is green

v0.5.0_beta
· New "App language": Settings → 🌐 Language, choose Follow system / 简体中文 / 繁體中文 / English / 日本語 / 한국어 — applies instantly, no restart
· The whole interface is now multilingual: home, camera, direct questions, solving, grading, notebook, review, settings, notifications
· "AI answer language" and "App language" are now two separate settings: one controls what language the AI answers in, the other the app's own text

v0.4.9
· Photo questions now show only the original image in the question bubble — the AI-transcribed text is gone (it is still saved; notebook, search and editing are unaffected, and older sessions behave the same). Direct questions still show what you typed
· New submenu in Settings: 🌐 Language → AI answer language (follow system / follow question / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Français / Español / Русский)
· The language setting covers photo solving, direct questions, follow-ups, grading and similar problems (formulas stay in LaTeX)

v0.4.8
· Questions and first prompts render as a light-green user bubble with the original image everywhere: one-shot, two-shot, direct questions, grading, notebook and review
· Review screen switched to a single column — full width, larger images
· R8 shrinking + resource shrinking became standard for release: APK about 4.4 MB (was about 30 MB), old sessions stay readable

v0.4.7.5 (temporary version)
· Enabled R8 code shrinking/obfuscation + resource shrinking (release only); APK much smaller; old sessions stay readable
· New submenu in Settings: 📝 What's new

v0.4.7
· Direct questions now appear in the conversation as "my question + attachments"
· Performance: notebook/review images use caching + downsampled decoding (smoother scrolling, less memory)
· Size: removed redundant KaTeX .woff files (kept woff2); preview tooling moved to debug-only
· Photo solving gained One-shot / Two-shot modes: two photos are solved as one problem
· More micro-optimizations: in-memory API key cache; cached flows; reused time formatter; cached message list; skip WebView re-render when content is unchanged; cached filters; preloaded category/tag names

v0.4.6
· "About" is shown directly on the settings page instead of a submenu
· Tapping the "generating" notification returns to the page that is generating

v0.4.5
· Settings became a submenu list (API Key / Theme / Review reminder / Background running / About)

v0.4.4
· Tag selection when saving a problem became a scrollable dropdown (AI suggestions kept)
· Notebook tag filter became a "Search by tag" button with a multi-select dropdown

v0.4.3
· Model upgraded to DeepSeek-V4.1-Flash (deepseek-flash, native multimodal), vision and text unified
· Fixed the "Solve" title being squeezed into an ellipsis by the right-side buttons

v0.4.2
· Tapping the review reminder opens the Review screen directly
· "Enable auto-start" shortcut in Settings (Honor / Huawei / Xiaomi / OPPO / vivo)
· "✏️ Ask directly" in the camera screen: ask with text + 1–3 images
· Reminder time picked with a wheel selector

v0.4.1
· After reviewing, jump to the next problem automatically (same subject first); exit only when today's list is done
· Similar-problem practice: the AI writes a similar problem (question first, interactive); tap "Show answer" to reveal it
· Review reminder notification: switch + time (once a day)
· Notification permission request; reminders restored after reboot

v0.4.0
· Review feature: new "📖 Review" entry on the home screen
· Ebbinghaus scheduling: new problems are scheduled at 0/1/2/4/7/15/30-day intervals (new review table, DB v7)
· Review screen with Today / This week tabs, grouped by subject → knowledge point, two-column waterfall
· Inside a problem the bottom shows Easy / Unsure / Forgot, changing when it appears next
· Similar-problem API prepared

v0.3.9
· Knowledge tags: decoupled from subject categories, global, many-to-many
· AI auto-tagging (up to 5, reusing existing tags), editable when saving or in details
· Multi-select tag filter at the top of the notebook; tags shown on cards
· Database v5→v6 (tags + question_tags)

v0.3.8
· Fixed: conversation body back to a single, stable scrolling WebView
· Fixed: problem timestamps record the asking time and no longer change when you leave
· Versioning convention: custom data files carry a 4-byte version header for future migration
· Key policy: no keys in the source or repository

v0.3.7
· Batch category change / batch delete: long-press in the notebook to multi-select
· Batch category: existing / new / uncategorized
· Batch delete: permanent deletion with a confirmation dialog
· Selected cards show a check mark and a green border

v0.3.6
· Problem categories: choose uncategorized / existing / new when saving; the AI guesses the subject
· The AI prefers existing categories to avoid duplicates
· Category filter at the top of the notebook; the category can be changed in details
· Notebook switched to a two-column waterfall; "Uncategorized" added
· Database v4→v5 (categories table)

v0.3.5
· Follow-ups can include images (1–3); the AI reads both text and images
· Input row restyled: rounded field + pill-shaped "Send" button
· Follow-up bubbles show attachment thumbnails; attachments are saved with the session
· Removed the "next problem" button; going back from Solve returns to the camera
· Camera supports tap-to-focus with a focus ring animation

v0.3.4
· Grading: one-shot / two-shot capture; the AI checks correctness, points out wrong steps and explains
· App icon letter changed to SA
· Notebook deletion became a soft delete (restorable)
· Follow-up input avoids the keyboard; the gallery button is aligned with the shutter

v0.3.3
· App icon (white background + blue book); launcher name "Study Assistant"
· Saving stores the whole conversation; tapping a problem continues the Q&A
· Crop frame in bold red with draggable corners; APK naming convention

v0.3.2
· Bottom tabs: Home / Settings
· Theme: light / dark / follow system
· API key moved into Settings; About shows author and version
· Fixed answer scrolling; answers shown as chat bubbles; first launch prompts for the key

v0.3.1
· Markdown formatting in the answer area
· Fixed long-answer scrolling; wide formulas adapt with horizontal scrolling
· "Retake" in the camera screen; "next problem" in the Q&A screen
· "Gallery search" in the camera screen; collapsible original image on the answer page
· Notebook shows the cropped original image and the LaTeX answer

v0.3.0
· Crop after taking a photo, or use the whole image
· Regenerate + continue the conversation (multi-turn)
· API key entered in the app (encrypted with Android Keystore)
· KaTeX formula rendering

v0.2 (early version)
· Switched image handling to DeepSeek: images go straight to the DeepSeek vision model for recognition and solving; Baidu OCR removed

v0.1 (project start)
· Initial build: Baidu OCR for image recognition + DeepSeek for solving; photo search, AI solutions and the mistake notebook prototype
""".trimIndent()

internal val CHANGELOG_JA: String = """
v0.5.0
· 設定を再構成：「💾 データ管理」を追加。「このアプリについて」をサブページ化（上にアプリ情報、下に更新内容）
· データ管理：バックアップ書き出し（画像と会話を含む 1 つの JSON）／読み込み（同名の分類・タグは再利用、同じ問題文はスキップ。既存データは上書きしません）／すべてのデータを削除（確認あり）
· 「🔑 API 管理」：キー設定に加えて累計利用状況（呼び出し回数・入力/出力 tokens、リセット可）。入力欄の下に「API Key は常に端末内に暗号化して保存されます」と表示
· 復習は既定で解答を隠す：まず自分で思い出し、「解答を見る」で展開。上部に「{i}/{n} 問目」の進捗を表示
· 間違いノートに全文検索を追加（問題文と解答）
· 直接質問と追加質問に数式入力バーを追加：∫ ∑ √ π などの記号＋LaTeX テンプレート（分数・上下付き・積分区間など）
· やり直しモードのインターフェースを予約（手書きで解き直し → 添削を再利用 → 習熟度を自動更新）。UI は後続版で
· 会話（解答／添削／類似問題）内の画像を**タップで全画面表示**：ピンチで拡大、ドラッグで移動、✕ か戻るで閉じる
· 「すべてのデータを削除」は**確認文の入力**が必要になり、確定ボタンは赤色。「API Key は暗号化保存」の注記は緑色に

v0.5.0_beta
· 「アプリの言語」を追加：設定 → 🌐 言語設定 で システムに従う / 简体中文 / 繁體中文 / English / 日本語 / 한국어 から選択。すぐに反映され、再起動は不要
· 画面の文言を全面的に多言語化：ホーム、撮影、直接質問、解答、添削、間違いノート、復習、設定、通知など
· 「AI の回答言語」と「アプリの言語」は別々の設定に：前者は AI の解答言語、後者はアプリ画面の言語

v0.4.9
· 撮影した問題のバブルは元の画像だけを表示：AI が文字起こしした問題文は表示しません（問題文は保存され、ノート・検索・編集には影響しません。過去のセッションも同様）。直接質問では入力した文章をそのまま表示
· 設定に「🌐 言語設定」→「AI の回答言語」を追加：システムに従う / 問題文に合わせる / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Français / Español / Русский
· 言語設定は撮影解答・直接質問・追加質問・添削・類似問題に適用（数式は LaTeX のまま）

v0.4.8
· 問題文・最初の質問を、元の画像つきの薄緑のユーザーバブルで統一表示：1枚撮影、2枚撮影、直接質問、添削、ノート、復習すべて同じ
· 復習画面を1列に変更：行いっぱいの幅で画像が大きく見やすく
· R8 による難読化＋リソース圧縮を標準化（release）：APK 約 4.4MB（従来約 30MB）。過去のセッションはそのまま読めます

v0.4.7.5（暫定版）
· R8 によるコード縮小・難読化＋リソース圧縮を有効化（release のみ）。APK が大幅に小さく。過去のセッションは互換
· 設定に「📝 更新内容」を追加

v0.4.7
· 直接質問が会話に「自分の質問＋添付画像」として表示されるように
· 性能：ノート・復習の画像をキャッシュ＋縮小デコード（スクロールが滑らか、メモリ節約）
· サイズ：KaTeX の不要な .woff を削除（woff2 は保持）。プレビュー用ツールは debug のみに
· 撮影検索に「1枚 / 2枚」モードを追加：2枚を1問としてまとめて解答
· さらなる効率化：API Key のメモリキャッシュ、Flow のキャッシュ、時刻フォーマッタの再利用、メッセージ一覧のキャッシュ、WebView の再描画スキップ、絞り込み結果のキャッシュ、分類・タグ名の事前読み込み

v0.4.6
· 「このアプリについて」をサブメニューにせず設定のトップに直接表示
· 生成中の通知をタップすると生成中の画面に戻る

v0.4.5
· 設定をサブメニュー化：API Key / テーマ / 復習リマインダー / バックグラウンド実行 / このアプリについて

v0.4.4
· 保存時のタグ選択をスクロール可能なドロップダウンに（AI の自動選択は維持）
· ノートのタグ絞り込みを「タグで検索」ボタン＋複数選択ドロップダウンに

v0.4.3
· モデルを DeepSeek-V4.1-Flash（deepseek-flash、ネイティブマルチモーダル）へ更新。視覚とテキストを統一
· 修正：解答ページのタイトル「解答」が右側のボタンに押されて省略記号になる問題

v0.4.2
· 復習リマインダーの通知をタップすると「復習」画面が開く
· 設定に「自動起動を有効化」（Honor / Huawei / Xiaomi / OPPO / vivo 対応）
· 撮影画面の右上に「✏️ 直接質問」：文字＋1〜3枚の画像で質問できる
· 通知時刻をホイールで上下に選択

v0.4.1
· 復習後は自動で次の問題へ（同じ科目を優先）。今日分をすべて終えると終了して通知
· 類似問題：AI が類似問題を出題（まず問題のみ表示、対話可）。「解答を見る」で解答表示
· 復習リマインダー通知：オン/オフ＋時刻（1日1回）
· 通知権限のリクエスト。再起動後にリマインダーを復元

v0.4.0
· 復習機能：ホームに「📖 復習」を追加
· エビングハウス式の自動スケジュール：新規の問題を 0/1/2/4/7/15/30 日の間隔で出題（review テーブル追加、DB v7）
· 復習画面は「今日 / 今週」を切替、科目→知識ポイントでグループ化、2列ウォーターフォール
· 問題を開くと下部が わかる / あいまい / わからない の3ボタンに。次回の出題時期が変わる
· 類似問題 API を用意

v0.3.9
· 知識タグ：科目分類から分離、全体で共通、多対多
· AI が自動でタグ付け（最大5個、既存タグを優先）。保存時・詳細画面で変更可能
· ノート上部でタグの複数選択絞り込み。カードにタグを表示
· データベース v5→v6（tags + question_tags）

v0.3.8
· 修正：会話本文を単一の WebView に戻し安定スクロール。ノートから開いた後も滑らかに
· 修正：問題のタイムスタンプは質問時刻を記録し、開いて戻っても更新されない
· バージョン規約：独自データファイルの先頭4バイトにバージョン番号
· キー方針：ソース／リポジトリに一切のキーを含めない

v0.3.7
· 分類の一括変更／一括削除：ノートで長押しして複数選択
· 一括分類：既存 / 新規 / 未分類
· 一括削除：完全削除（確認ダイアログ）
· 選択カードにチェック印＋緑の枠

v0.3.6
· 問題の分類：保存時に「未分類 / 既存 / 新規」を選択。AI が科目を推定
· AI は既存の分類を優先し、重複作成を避ける
· ノート上部で分類絞り込み。詳細画面で分類変更
· ノートを2列ウォーターフォールに。「未分類」を追加
· データベース v4→v5（categories）

v0.3.5
· 追加質問に画像を添付可能（1〜3枚）。AI は画像と文字の両方を読んで解答
· 入力欄のデザイン：角丸の入力欄＋カプセル型「送信」ボタン
· 追加質問のバブルに添付画像のサムネイルを表示。画像はセッションと共に保存
· 「次の問題を撮る」ボタンを削除。解答ページの戻るは撮影画面へ直行
· 撮影／添削カメラでタップフォーカス＋フォーカス枠アニメーション

v0.3.4
· 添削：1枚／2枚撮影。AI が正誤を判定し、誤った手順を指摘して解説
· アプリアイコンの文字を SA に変更
· ノートの削除をソフト削除に（「復元」可能）
· 追加質問欄のキーボード回避。図庫ボタンをシャッターと水平に

v0.3.3
· アプリアイコン（白地＋青い本）。ランチャー名 Study Assistant
· 保存時に会話全体を保存。問題をタップして问答を続けられる
· 範囲選択は赤い太線、四隅をドラッグ可能。APK 命名規約

v0.3.2
· 下部タブ：ホーム / 設定
· テーマ：ライト / ダーク / システムに従う
· Key 設定を設定画面へ移動。このアプリについてに作者とバージョン
· 解答のスクロール修正。解答をチャットバブルに。初回起動時に Key 入力ダイアログ

v0.3.1
· 解答欄が Markdown に対応
· 長い解答のスクロール修正。横幅の広い数式は自動縮小＋横スクロール
· 撮影画面に「撮り直す」、问答画面に「次の問題を撮る」
· 撮影画面に「アルバムから検索」。解答ページ上部で元画像を折りたたみ
· ノートに切り抜き元画像を表示し、LaTeX 解答を閲覧可能

v0.3.0
· 撮影後の範囲選択：問題部分を切り抜くか、画像全体で認識
· 再生成＋会話の継続（複数ターン）
· API Key をアプリ内で入力（Android Keystore で暗号化保存）
· KaTeX による数式レンダリング

v0.2（初期版）
· 画像処理を DeepSeek に変更：画像を直接 DeepSeek の視覚モデルに送って認識・解答。Baidu OCR は廃止

v0.1（プロジェクト開始）
· プロジェクト構築：Baidu OCR で画像認識＋DeepSeek で解答。撮影検索・AI 解答・間違い整理の原型
""".trimIndent()

internal val CHANGELOG_KO: String = """
v0.5.0
· 설정 재구성: '💾 데이터 관리' 추가, '정보'를 하위 페이지로 변경(위는 앱 정보, 아래는 업데이트 내역)
· 데이터 관리: 백업 내보내기(이미지와 대화를 포함한 단일 JSON) / 백업 가져오기(같은 이름의 분류·태그는 재사용, 문제 문장이 같으면 건너뜀 — 기존 데이터를 덮어쓰지 않음) / 모든 데이터 삭제(확인 절차)
· '🔑 API 관리': 키 설정과 함께 누적 사용량(호출 횟수, 입력/출력 tokens, 초기화 가능)을 표시하고, 입력창 아래에 'API Key는 항상 기기 내에 암호화되어 저장됩니다'를 안내
· 복습은 기본적으로 정답을 숨김: 먼저 스스로 떠올린 뒤 '정답 보기'로 펼침. 상단에 '{i}/{n}' 진행 표시
· 오답 노트에 전체 검색 추가(문제 문장과 풀이)
· 직접 질문과 추가 질문에 수식 입력 바 추가: ∫ ∑ √ π 기호와 LaTeX 템플릿(분수, 위/아래 첨자, 적분 구간 등)
· 다시 풀기 모드의 인터페이스를 미리 마련해 둠(손으로 다시 풀기 → 채점 재사용 → 숙련도 자동 갱신). UI는 다음 버전에서
· 대화(풀이/채점/유사 문제) 안의 이미지를 **탭하면 전체 화면으로 확대**: 두 손가락 확대, 드래그 이동, ✕ 또는 뒤로 가기로 닫기
· '모든 데이터 삭제'는 **확인 문장을 직접 입력**해야 실행되며 확인 버튼이 빨간색입니다. 'API Key 암호화 저장' 안내는 초록색으로

v0.5.0_beta
· '앱 언어' 추가: 설정 → 🌐 언어 설정에서 시스템 설정 따르기 / 简体中文 / 繁體中文 / English / 日本語 / 한국어 선택. 즉시 적용되며 재시작이 필요 없습니다
· 화면 문구를 전면 다국어화: 홈, 촬영, 직접 질문, 풀이, 채점, 오답 노트, 복습, 설정, 알림 등
· 'AI 답변 언어'와 '앱 언어'가 별도 설정으로 분리: 전자는 AI가 답하는 언어, 후자는 앱 화면 언어

v0.4.9
· 촬영 문제의 말풍선은 원본 이미지만 표시: AI가 옮겨 적은 문제 문장은 더 이상 표시하지 않습니다(문장은 저장되며 노트·검색·편집에 영향 없음, 이전 세션도 동일). 직접 질문은 입력한 문장을 그대로 표시
· 설정에 '🌐 언어 설정' → 'AI 답변 언어' 추가: 시스템 설정 따르기 / 문제 언어 따르기 / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Français / Español / Русский
· 언어 설정은 촬영 풀이, 직접 질문, 추가 질문, 채점, 유사 문제에 적용됩니다(수식은 LaTeX 유지)

v0.4.8
· 문제와 첫 질문을 원본 이미지가 포함된 연녹색 사용자 말풍선으로 통일: 1장 촬영, 2장 촬영, 직접 질문, 채점, 노트, 복습 모두 동일
· 복습 화면을 1열로 변경: 한 줄 전체 폭, 이미지가 더 크고 선명하게
· R8 난독화 + 리소스 축소를 기본으로 적용(release): APK 약 4.4MB(기존 약 30MB), 이전 세션 호환 유지

v0.4.7.5 (임시 버전)
· R8 코드 축소/난독화 + 리소스 축소 활성화(release 전용). APK 크기 대폭 감소, 이전 세션 호환
· 설정에 '📝 업데이트 내역' 추가

v0.4.7
· 직접 질문이 대화에 '내 질문 + 첨부 이미지'로 표시됩니다
· 성능: 노트·복습 목록 이미지를 캐시 + 축소 디코딩(스크롤이 부드럽고 메모리 절약)
· 용량: 불필요한 KaTeX .woff 삭제(woff2 유지), 미리보기 도구는 debug 전용으로
· 촬영 검색에 '1장 / 2장' 모드 추가: 두 장을 한 문제로 함께 풀이
· 추가 최적화: API Key 메모리 캐시, Flow 캐시, 시간 포매터 재사용, 메시지 목록 캐시, WebView 재렌더링 생략, 필터 결과 캐시, 분류/태그 이름 사전 로드

v0.4.6
· '정보'를 하위 메뉴가 아니라 설정 첫 화면에 바로 표시
· 생성 중 알림을 탭하면 생성 중인 화면으로 돌아갑니다

v0.4.5
· 설정을 하위 메뉴로 개편: API Key / 테마 / 복습 알림 / 백그라운드 실행 / 정보

v0.4.4
· 문제 저장 시 태그 선택을 스크롤 가능한 드롭다운으로(AI 자동 선택 유지)
· 노트의 태그 필터를 '태그로 검색' 버튼 + 다중 선택 드롭다운으로

v0.4.3
· 모델을 DeepSeek-V4.1-Flash(deepseek-flash, 네이티브 멀티모달)로 업그레이드. 시각과 텍스트 통합
· 수정: 풀이 화면 제목 '풀이'가 오른쪽 버튼에 밀려 말줄임표가 되던 문제

v0.4.2
· 복습 알림을 탭하면 '복습' 화면이 바로 열립니다
· 설정에 '자동 시작 한 번에 켜기'(Honor/Huawei/Xiaomi/OPPO/vivo 대응)
· 촬영 화면 오른쪽 위 '✏️ 직접 질문': 텍스트 + 1~3장 이미지로 질문
· 알림 시간을 휠로 위아래 선택

v0.4.1
· 복습 후 자동으로 다음 문제로(같은 과목 우선). 오늘 분을 모두 끝내면 종료하고 안내
· 유사 문제: AI가 유사 문제를 출제(먼저 문제만 표시, 대화 가능), '정답 보기'를 눌러야 정답 표시
· 복습 알림: 켜기/끄기 + 시간(하루 한 번)
· 알림 권한 요청, 재부팅 후 알림 자동 복원

v0.4.0
· 복습 기능: 홈에 '📖 복습' 추가
· 에빙하우스 자동 스케줄: 새 오답을 0/1/2/4/7/15/30일 간격으로 배치(review 테이블 추가, DB v7)
· 복습 화면 '오늘 / 이번 주' 탭, 과목→지식 포인트 그룹, 2열 워터폴
· 문제를 열면 하단이 익숙함 / 애매함 / 모름 3버튼으로 바뀌어 다음 출제 시점 변경
· 유사 문제 API 준비

v0.3.9
· 지식 태그: 과목 분류와 분리, 전역 공통, 다대다
· AI 자동 태깅(최대 5개, 기존 태그 우선). 저장 시/상세 화면에서 수정 가능
· 노트 상단에서 태그 다중 선택 필터, 카드에 태그 표시
· 데이터베이스 v5→v6(tags + question_tags)

v0.3.8
· 수정: 대화 본문을 단일 WebView로 되돌려 스크롤 안정화. 노트에서 열어도 부드럽게
· 수정: 문제 타임스탬프는 질문 시각을 기록하고, 열었다 나가도 갱신되지 않음
· 버전 규칙: 사용자 데이터 파일 앞 4바이트에 버전 번호 기록
· 키 정책: 소스/저장소에 어떤 키도 포함하지 않음

v0.3.7
· 분류 일괄 변경 / 일괄 삭제: 노트에서 길게 눌러 다중 선택
· 일괄 분류: 기존 / 새로 만들기 / 미분류
· 일괄 삭제: 완전 삭제(확인 대화상자)
· 선택 카드에 체크 표시 + 초록 테두리

v0.3.6
· 문제 분류: 저장 시 '미분류 / 기존 / 새로 만들기' 선택, AI가 과목 추정
· AI는 기존 분류를 우선 사용해 중복 생성을 방지
· 노트 상단 분류 필터, 상세 화면에서 분류 변경
· 노트를 2열 워터폴로 변경, '미분류' 추가
· 데이터베이스 v4→v5(categories)

v0.3.5
· 추가 질문에 이미지 첨부 가능(1~3장). AI가 이미지와 텍스트를 함께 읽고 답변
· 입력줄 디자인: 둥근 입력창 + 캡슐형 '전송' 버튼
· 추가 질문 말풍선에 첨부 이미지 썸네일 표시, 이미지는 세션과 함께 저장
· '다음 문제 촬영' 버튼 제거, 풀이 화면에서 뒤로 가면 촬영 화면으로
· 촬영/채점 카메라에서 탭 초점 + 초점 애니메이션 지원

v0.3.4
· 채점: 1장/2장 촬영, AI가 정오를 판단하고 틀린 단계를 짚어 설명
· 앱 아이콘 글자를 SA로 변경
· 노트 삭제를 소프트 삭제로(복원 가능)
· 추가 질문 입력창 키보드 회피, 갤러리 버튼을 셔터와 같은 높이로

v0.3.3
· 앱 아이콘(흰 배경 + 파란 책), 런처 이름 Study Assistant
· 저장 시 전체 대화를 저장, 문제를 탭해 대화를 이어갈 수 있음
· 선택 영역은 굵은 빨간색, 네 모서리를 드래그 가능, APK 이름 규칙

v0.3.2
· 하단 탭: 홈 / 설정
· 테마: 라이트 / 다크 / 시스템 설정 따르기
· Key 설정을 설정 화면으로 이동, 정보에 만든이와 버전 표시
· 답변 스크롤 수정, 답변을 채팅 말풍선으로, 첫 실행 시 Key 입력 대화상자

v0.3.1
· 답변 영역 Markdown 지원
· 긴 답변 스크롤 수정, 넓은 수식은 자동 축소 + 가로 스크롤
· 촬영 화면에 '다시 촬영', 대화 화면에 '다음 문제 촬영'
· 촬영 화면에 '앨범에서 검색', 답변 페이지 상단에서 원본 이미지 접기
· 노트에 잘라낸 원본 이미지를 표시하고 LaTeX 풀이 확인 가능

v0.3.0
· 촬영 후 영역 선택: 문제 부분만 잘라내거나 전체 이미지로 인식
· 다시 생성 + 대화 이어가기(다중 턴)
· API Key 앱 내 입력(Android Keystore 암호화 저장)
· KaTeX 수식 렌더링

v0.2 (초기 버전)
· 이미지 처리를 DeepSeek로 전환: 이미지를 DeepSeek 비전 모델에 직접 보내 인식·풀이. Baidu OCR 제거

v0.1 (프로젝트 시작)
· 프로젝트 구축: Baidu OCR 이미지 인식 + DeepSeek 풀이. 사진 검색·AI 풀이·오답 정리 초기 형태
""".trimIndent()

/** 按界面语言取更新内容（繁體中文由简体自动转换） */
internal fun changelogText(lang: UiLang): String = when (lang) {
    UiLang.EN -> CHANGELOG_EN
    UiLang.JA -> CHANGELOG_JA
    UiLang.KO -> CHANGELOG_KO
    UiLang.ZH_TW -> s2t(CHANGELOG_ZH)
    else -> CHANGELOG_ZH
}
