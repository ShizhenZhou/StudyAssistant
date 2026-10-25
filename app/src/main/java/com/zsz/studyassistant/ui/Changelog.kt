package com.zsz.studyassistant.ui

/**
 * 版本更新内容（与项目 README 的「版本记录」保持一致）。
 * 支持 4 种语言；繁體中文由简体经 s2t() 自动转换（见 L10n.kt 的映射表）。
 */
internal val CHANGELOG_ZH: String = """
v0.6.6.12
· 💾 加固对话草稿保存与恢复，旧请求的清理不再影响新草稿；尚未存入错题本或搜题记录的完整草稿继续保留
· 🧹 主动清空学习数据时同步清除对话草稿并停止旧写入，确认提示明确包含搜题记录和草稿
· 📖 区分复习保存失败与下一题读取失败；已保存后的切题重试不再重复登记复习或增加进度
· 🛡️ 大题干、答案和原图分段读取，列表使用短预览；全文搜索、查重和备份仍保留完整内容
· 🔢 保留 Markdown 小题的原始数字序号，避免非连续编号被自动改写
· 🌐 统计页掌握程度标签随界面语言切换，补齐四语言提示

v0.6.6.11
· 🎨 数据管理入口与 AI 管理共用标题卡片，去掉按钮下方说明并保留于子页；关于页“技术与兼容”补齐背景框
· ✍️ 自定义要求独立为 AI 管理下的子页，保留键盘上方保存按钮，更新页面树与返回路径
· 📝 补回 v0.6.6.9、v0.6.6.10 更新内容及关于页功能、隐私条目的 emoji

v0.6.6.10
· ⚙️ 自动备份并入备份管理；AI 管理拆分为 API 管理、AI 切图、解题设置，统一返回路径
· ↩️ 删除错题、记录或分类后可在 3 秒内撤销，之后才实际删除
· 💬 重新生成成功前保留旧答案，完成追问及时保存；中断对话独立保存在本机，可恢复草稿
· 📖 复习固定本轮筛选范围，保存成功才进入下一题；AI 批改由用户确认，修正自然日计算，统计标明估算口径
· 📥 重复导入只为新错题引入所需分类和标签；明确救援导出用途
· 🛡️ 列表图片按需读取，大对话分段加载，备份超出安全内存范围时停止并保留原数据
· 🛠️ 请求独立中止；完善提醒点击与排期，以及更新下载取消、临时文件和后台校验

v0.6.6.9
· 📝 收紧编号清洗规则，保留结论和过程里重复出现的圈号小题编号
· 💾 备份导出在后台打开、写入并关闭文件，减少卡顿与额外内存占用；完成前保持忙碌状态，防止重复操作
· 💬 识别 AI 长度上限及异常中断，保留已收到内容并提示手动继续或重试，不自动重复请求
· 🛠️ 修正本地签名摘要计算依赖，发布失败原因更明确且不显示凭据

v0.6.6.8
· 💾 日常保存保持静默；备份和操作结果使用官方 Material 3 悬浮胶囊气泡，不挤动页面，失败红字并延长停留
· 🛠 保存失败可从气泡或数据管理进入重试、导出；备份导出在实际写入文件后才提示成功
· 🖼 对话附图按钮改为“查看图片”，适用于原题和追问附图，补齐四语言
· 📝 解答结论与详细过程之间加入分隔线；保留原题小题编号，结论与推导逐题对应，允许正常步骤编号
· 💬 简短追问和概念解释不强制套完整解题结构；已有答案不变，DB v10 / Bak v3 不变

v0.6.6.7
· 💾 保存失败保留待保存副本，支持重启恢复、重试及手动导出；恢复冲突不覆盖，明确放弃才清除副本
· 🛠 退出错题页仅保存题干、答案和对话，保留当前分类、标签与复习状态；导入或清空前先处理未保存内容
· 🔒 移除 AI 原始响应调试日志
· ⬆️ 同版本更新按 APK 的 UTC 构建时间比较，附件命名与内嵌时间统一；旧包使用摘要兜底，相同内容不重复提示
· 🧪 严格 JVM 单测，标签发版与手动检查可在独立模拟器实际运行 Android 数据测试；DB v10 / Bak v3 不变

v0.6.6.6
· ℹ️ 校准关于页与项目介绍：最低 Android 9，快速/思考均使用 deepseek-flash；准确说明题干和图片直发官方 API、标签分析范围及本地导出
· 💾 自动备份失败在设置页保留原因与时间，成功写入并读回校验后清除警示；切换文件夹重置状态，启动不增加弹窗
· 📝 标签整理草稿自动保存在本机；恢复时核对当前标签与关联，仍须重新预览后确认合并；保存失败有提示，可明确放弃草稿
· 🔎 AI 标签分析保留最近 20 条本机诊断，可手动导出或清空；仅记录耗时、模式、状态与数量，不含标签名称、题目、响应原文或密钥
· 🧪 增加独立包名的 Android 数据库与文件测试，开发检查统一入口；保持 DB v10 / Bak v3，无新增主库迁移

v0.6.6.5
· 🛠 同版本附件修正：修正思考模式标签分析立即返回 HTTP 422 的参数问题；分析失败显示具体原因与状态码，版本号与数据格式不变
· 🏷 标签建议读取修正：使用 JSON 输出，保留可编辑的有效候选，区分截断、空结果与格式错误；候选默认不勾选，合并前仍严格检查冲突
· 🏷 新增标签整理：AI 根据完整标签名单和关联题数提出近义标签建议；可手动分组、改名与调整来源标签，预览确认后才合并
· ↩️ 合并按事务迁移关联并去重，保留题目永久编号；支持重启后撤销最近一次合并，相关数据再次变化时停止撤销以保留后续编辑
· 🔒 AI 分析只发送标签编号、名称及题数，不发送题目正文、答案或图片；与解题会话独立，可取消
· 💾 主库保持 DB v10，撤销记录存入独立本机辅助库；新合并成功后覆盖旧记录，备份仍为 v3，开始导入或清空前使旧撤销记录失效

v0.6.6.4
· 💾 自动备份写入后读回并校验 SHA-256，通过后才清理旧备份；备份与清理串行执行，失败时尝试清理本次不完整文件
· 📥 备份在后台读取和校验，显示处理状态；图片逐题解码，降低导入时的内存峰值，保持整次事务回滚
· 🕘 搜题记录保存失败显示提示并支持重试；保留最新快照与原编号，已删除或过期的记录不因重试恢复
· 📌 DB v10、备份 v3 不变，无新增数据迁移

v0.6.6.3
· 🛠 同版本附件更新：首次成功确认数据库升级显示“升级成功”，之后无变更显示“无需升级”；升级 / 修复页显示 DB / Bak 与实际数据库版本，旧格式数据以橙黄色警示
· 🆔 题目改用 UUID＋首次输入时间作为永久编号；错题本、图片、标签及复习进度原地迁移至 DB v10，迁移前保存数据库副本
· 🕘 搜题记录只在首次拍题或提交提问时创建；错题本/复习打开不新增，追问、重试和重新生成不改变首次时间及排序
· 💾 备份 v3 按永久编号去重并维护关联；重复导入保留本机内容，不再按题干合并；坏图片或无效关联会中止导入
· ⚠️ 本版不再导入 v1/v2 老备份，升级后请重新导出 v3；已有错题自动迁移，缺失时间按相邻旧编号题目补齐

v0.6.6.2
· ℹ️ “关于”页增加 GPT-6.1 Sol 辅助编写说明，保留 DeepSeek-V4/V4.1
· 🕘 统一搜题记录过期清理的时间兜底，避免有效记录被误删
· 📚 从搜题记录打开已存错题时读取最新内容、分类和标签；对应错题已删除时独立查看，不自动恢复
· 💾 隔离题目保存状态，避免快速切换时串写；事务保存关联数据，原图仅追加缺失内容
· 🛠 数据升级提示按 DB 版本管理，不随小版本重复提示；一键升级无需变更时提示“无需升级”
· 📌 DB v9、备份 v2 不变；无原图来源的旧题无法恢复，手动修复的进一步改进暂缓

v0.6.6.1
· 🧭 **页面跳转理顺成"树"**：新增 `ui/AppNav.kt`（全应用页面树 = 唯一导航事实源）。进解题页要带上**来路**（home / camera / grade / history / notebook / review），返回按来路走 —— 修掉「从**搜题记录**打开一道拍过照的题，返回却跳到**拍照界面**」
· 🔤 **顶栏标题字号统一**：设置子页面（AI 管理 / 语言 / 主题 / 通知 / 数据管理 / 应用更新 / 关于）、搜题记录、错题本、复习的标题，全部与**解题页**一致（19sp；返回箭头仍 21sp）
· 🕘 **搜题记录顶栏标题去掉 emoji**（主页按钮上的 🕘 保留）
· 📄 修「应用更新」页里 v0.6.6 与上一版之间少一个空行

v0.6.6
· 🗂 数据结构升级：一题多图改为**正经入库**（新表 `question_images`）；**v8 及更老数据自动迁移**，迁移前自动备份数据库文件
· 💾 备份升级：包含**全部原图 + 搜题记录**；导入改为事务（失败回滚），**旧备份仍可导入**
· 🛠 数据管理拆成**四个子页**（备份管理-导出导入 / 自动备份 / 崩溃日志 / 数据升级·修复），**「清空所有数据」挪到数据管理一级页**（红色危险项，要输入确认词才可执行）
· ⚠️ 更新后首次进入若检出旧格式数据会提示升级（可跳过，不反复打扰）

v0.6.5.3
· 🐞 **修复「两张图仍会丢一张」**：读取端原来"有主图就只用主图"，而其余图只在会话数据里 → 双图打开只剩一张；现在统一**合并主图 + 会话附图**（按 base64 去重，最多 3 张），错题本详情 / 复习自测 / 搜题记录三处一起修好

v0.6.5.2
· 🎛 **取消「打开题目 / 打开分类对话框时 AI 实时判断」**：分类与标签只来自解答时的预置，打开已存题只读库里信息，没有就默认「暂不分类 + 不勾选标签」—— 顺带修好"AI 擅自改写已存分类"与"取消勾选标签失灵"
· 🖼 **复习自测显示全部原图**（多图 / 图文提问存下的错题不再只看第一页，最多 3 张）
· 🔤 **错题本右上角按钮与解题页同字号**：🔍 搜索 / ⇅ 排序 / 管理 统一用解题页「⏸ 中止 / 🔄 重新生成」同一个字号常量（13sp）

v0.6.5.1
· 🐞 **修复「存错题本后原图丢失」**（多图拍题 / 图文提问更容易遇到）：原图一直在会话里，只是没写进专用图片字段 → 现在保存时补上，并在启动时**自动回填历史错题**（错题本卡片 / 复习页自测 / 备份一起恢复）
· 🔤 **按钮字号统一**：错题本右上角的 🔍 搜索 / ⇅ 排序 / 管理 与解题页顶栏同字号（原来 12sp 偏小 → 13sp）
· 🔤 **多选顶栏统一**：搜题记录与解题页长按多选时，「全选 / 删除 / 完成」字号与解题页按钮一致（错题本的多选顶栏排得紧，按要求不动）

v0.6.5
· 🕘 **新增「搜题记录」**（首页第 5 个入口）：解答过的题目自动留下，没点「存错题本」就退出也不怕丢；按天分组、点开可继续追问、长按多选删除；保留最近 200 条 / 30 天，原图一并保留
· 🐞 **修复切图页「上边框偶尔蹦到最上边」**：接上了一道写了却从未启用的「贴边就丢弃」防线；手指按下即锁定手动框选；缩放松手 / 换图后把选框收回图内；四角热区补外侧边界
· 🐞 **修复搜题记录被分到「更早」甚至被清理误删**：追问 / 重新生成会把创建时间写成 0 → 被当成「过期 30 天」删掉；现已修正写入并加兜底
· 🎛 **「自定义要求」的保存按钮改为跟随键盘出现**（键盘收起时页面干净，弹出时贴在键盘上方），并修掉键盘与输入框之间的多余空白
· 🏠 首页入口排布改为：拍照搜题 / 图文提问 各占一行 → 错题本 · 复习 → 搜题记录（不再出现「…」）

v0.6.4.5
· 📚 **「📚 存错题本」与「📁 分类」占位完全对齐**：「存错题本」按钮也按「📁 + 5 个汉字」的宽度给、文字左对齐、多余宽度留白在右侧 —— 两种状态切换时右侧按钮组**一动不动**
· ⌨️ **修复「自定义要求（Prompt）」输入到底部被键盘挡住**：设置二级页补上键盘避让（键盘弹出时整页内容上移），输入框加了高度上限、超出改为**框内滚动**，光标始终可见

v0.6.4.4
· 🐞 **修复「自定义要求」从未生效**：设置 → AI 管理里的自定义 Prompt **从上线起就没被发出去过**（代码写了却从未接线）→ 现在解题 / 批改 / 同类题 / 追问都会带上它
· 🐞 **修复「AI 看不到已有分类」**：发给模型的提示词**一直写着「已知分类：（当前没有任何分类）」**，于是模型自己造科目 —— 错题本里出现「大学物理」和「物理」这种重复分类。根因是取分类名的那个 Flow 从来没人订阅、值恒为空表，已改为常驻读取
· 🐞 **修复「有时把不该加粗的字加粗」**：`2**10 与 3**5` 这类会被渲染成跨词加粗（对话区 / 详情页 / 列表预览三处同修）
· 🐞 **修复「图文提问不提问直接返回，却回到拍照界面」**
· 🐞 **修复「正在思考的气泡中止后不能选中删除」**
· 📁 **修复「分类」按钮 5 个字显示不全**（如「信号与系统」只显示前 4 个字）：宽度测量改用按钮真实的字体样式
· 🔤 **默认提示词去冗余、消矛盾**（原来「每行都必须带序号」与「解答可多行」自相矛盾）；提示词里不再用星号示范加粗
· 📷 切图页**去掉「已自动框选题目范围」那行提示**（它出现/消失会让图片区高度变化、画面抽搐一下）

v0.6.4.3
· 🐞 **修复复习换题后「下一题」空白页**：从**图片题**切到**没有原图的文字题**时，题目的"轻/重查询重定向"会**再加载一次**并顺手把复习模式清掉 → 表现为**熟悉 / 模糊 / 忘记 三按钮消失**、标题由「复习 x/n」变回「错题」，偶发还**永久卡在居中的「下一题」空白页**。现在复习模式在整条加载链里都不会被清掉，清屏标志也加了兜底复位
· 📁 **「分类」按钮再修**：**emoji 字号任何时候都不变**（原来会跟着分类名一起缩小）；按钮**加宽到能放下 5 个汉字**（右侧按钮组因此整体左移一个汉字）；超过 5 字**不再显示「…」**，而是**自动调小字号**尽量完整显示（实在放不下才换第二行）
· ↩️ **所有子页面的返回箭头统一样式**：统一为解题页那种 **21sp 的大箭头**（设置二级页原来漏了字号 → 又小、又因容器内边距显得**靠右**）；相机 / 批改页的「← 返回」也一并统一
· 🔄 **「应用更新」页与 README 版本记录同步**：`0.x.x.x` 这种小修补的更新内容**也写进 App 内「更新内容」**（此前只写 README），历史四段版本一并补齐

v0.6.4.2
· 📁 **「分类」按钮占位固定**：尺寸锁定为「⏸ 中止生成 / 🔄 重新生成」按钮的实测宽高，分类名短（≤4 字）时 emoji 不挪位、右侧留白；名字变长自动降字号档位（13 → 12 / 11 / 10 / 9sp），9 字以上允许两行
· 📷 **拍照单击对焦同时调曝光**：单击测光显式带 `AF|AE|AWB`，并按预览帧亮度统计在约 1.2 秒内自动调整曝光补偿 —— 治「拍电脑屏幕过曝、字看不清」
· 🔍 **对焦框加大**：原来写死 42 像素（3.5× 屏上仅约 12dp）太小，改用 dp（整框约 64dp）

v0.6.4.1
· 🔤 **解题页顶栏字号再小 1sp**（用户要求）：⏸ 中止生成 / 🔄 重新生成 / 📚 存错题本统一 14 → **13sp**；「📁 分类」的自动缩放同步下调为 **11 / 10 / 9sp**
· 🧠 **思考区展开/折叠「记住最后一次操作」**：手动切一次之后，**全应用、跨题目**都按最后一次（展开 / 折叠）显示（存本机偏好 `think_expand_pref`，解题页与同类题页共用）
· 🐞 **修复荣耀机型误触长按**：点系统「灵动胶囊」后轻点屏幕，偶发被判定为「长按气泡」而进入多选 —— 现在网页侧补 `touchcancel`/失焦复位并加长按超时守卫，原生侧在窗口重新聚焦时复位手势状态

v0.6.4
· ⛔ **取消「导出这道题」功能**：长图 / PDF 导出**始终无法保证内容完整**（多次修复后仍会在底部截断），决定不再做这个功能 —— 解题页顶栏的 📤 按钮、导出弹窗、相关代码与文案**全部删除**。需要留档时可用系统截图，或长按复制题干与解答
· ✂️ **新增「AI 切图」开关**（设置 → AI 管理，位于「API 用量统计」下方）：**关掉后完全不调用 AI**，框选只用本地算法（更快、也不消耗额度），下面的「AI 框选时限」设置项随之隐藏；框选页的手动「AI 识别」按钮也不再显示
· 🔤 **「中止 / 重新生成」恢复文字**：顶栏显示「⏸ 中止生成」「🔄 重新生成」（不再只有图标），字号与「存错题本」等按钮一致
· 🔍 **错题本搜索按钮字号**：标题栏的 🔍 与右侧「排序 / 管理」等按钮**字号统一**

v0.6.3.3
· 🐞 **导出截断的真凶找到并修掉**：内容高度是在**原题图（base64 `<img>`）还没加载完**时量出来的 → 位图比真实内容矮 → 底部被切。现在页面里**等图片 + 字体都就绪**再上报高度，并且**截图前原生再量一次取最大值**；页面高度计算也把**页脚**算进去
· 🔤 **顶栏更紧凑**：📤 与 ⏸/🔄 两个图标按钮显式收紧到 44dp 宽、内边距 2dp（TextButton 默认 58dp 最小宽度会让单个 emoji 显得很"散"），整组贴到右侧

v0.6.3.2
· 🐞 **再修导出截断**：改成「**把导出页显示出来 + PixelCopy 逐带拷屏幕上真实像素**」，彻底不再依赖离屏 `draw`（它在实机上要么画不出内容、要么只画第一带）；每一带都要求**有内容**且**与上一带不同**，否则重试该带，仍失败就明确报「导出失败」——不会再给半截图
· 🔤 **顶栏按钮统一**：「中止 / 重新生成」**只保留 emoji**（⏸ / 🔄）；**📤 导出、⏸·🔄、📚 存错题本 三个按钮字号一致**；「分类」按钮（存完错题本后出现）**保留按分类名长度自动缩放**

v0.6.3.1
· 🐞 **修复导出全是空白**：此前离屏截图时网页被按 **1 像素宽**排版（PNG 成了 1px 白条、PDF 出来 11 页几乎全空）—— 现在挂载时就用真实宽度布局，并强制 **软件渲染** + 截完检查"有没有墨迹"，空白会明确报失败而不是存一张白图
· 📄 **PDF 改走 Chromium 打印**：由 WebView 自己按 A4 分页输出**矢量** PDF（比"长图切片"清晰得多）；导出固定浅色底（省墨、好看）
· 📤 **导出按钮挪到「重新生成」左边**（解题页顶栏）；**解题页标题再小 2sp**（21 → 19）

v0.6.3
· 📤 **新增「导出这道题」**：解题页顶栏最右侧的 📤 → 弹窗里**先选格式（PNG 长图 / PDF）**，再**选保存位置**（系统「保存到文件」）。导出内容 = 原题图 + 题干 + 解答 + 分类 / 知识点，公式用 KaTeX 正常排版；PDF 按 A4 自动分页
· 🔠 **新增「字号」设置**（设置 → 应用主题）：小 / 标准 / 大 / 特大 —— 只调整 App 内的文字大小，**界面与对话区同步**，不动系统设置
· 🔤 **整体字号收敛**：按钮字号统一**小 1~2 个字号**；内部页面（错题本 / 复习 / 解题 / 同类题 / 设置二级页）的左上角标题**小 1 个字号**，并且标题离「←」更近一点
· 📚 **错题本**：标题不再带题数（只写「错题本」），题数挪到「按标签搜索」那一行右侧，显示**当前筛选后的条数**「当前列表共 N 题」
· 🩹 修复：打开旧错题时的「按需补分类/标签」偶尔被丢弃（正好有另一个分类请求在跑）→ 补完这次会**自动再补一次**，旧题的科目与标签更可靠

v0.6.2
· ✏️ **复习可「手写重做」**：复习题目时点「✏️ 手写重做」→ 拍下你在纸上写的解答 → AI 批改并**按批改结果自动更新掌握度**（不用再手点熟悉/模糊/忘记）；批改完可直接「下一题」，纯文字题也能重做
· 📚 **错题本排序**：右上角新增「⇅ 排序」——最近添加 / 最早添加 / 下次复习时间 / 复习次数 / **最不熟优先**（选择会记住）
· 🔎 **复习筛选**：复习页可按**科目**筛选，也可「**只看不熟**」；进度按筛选后的题数算，筛空时会明确提示
· ⚠️ **存题查重**：题干相同的题在保存前会提示「**可能已存过**」并给出已有题干，可「仍然保存」（只提示、不阻止；同一道题两次转写有细微差异也能识别）
· 🐞 修复：**切换错题本排序后会跳到列表底部**（现在改完排序回到顶部）；复习页切换「今天/本周」或筛选时同样回到顶部
· 🐞 修复：**同一道题拍两次后存错题本查不出重复** —— 拍题时 AI 的两次转写不会逐字相同（LaTeX 记号/标点常有小差异），现在按「题干相同或高度相似」判定（短题干仍只比完全相同，避免误报）
· 🗄 **自动备份**：设置 → 数据管理 → 「自动备份」，选一个文件夹后**每天第一次打开应用**自动把错题本备份成 JSON，**只保留最近 3 份**（只存本机、不联网、不上传）
· 🌙 **深色主题适配对话区**：深色下气泡 / 正文 / 思考块配色跟随主题；此前深色下气泡仍是浅灰、思考块灰字几乎看不清
· 🐞 **崩溃日志（只存本机）**：闪退时自动记录版本 / 机型 / 完整堆栈（最多留 10 份），设置 → 数据管理里可一键**导出 txt** 或清空
· ⬆️ **更新页增强**：查到新版本时**直接显示该版本的更新说明**、可「**跳过此版本**」（之后不再顶红点）、显示**上次检查时间**、检查/下载失败可「重试 / 重新下载」
· 🔍 **内部质量**：新增 47 个单元测试（答案解析 / 版本比较 / 备份裁剪 / 排序 / 查重 / 文案表），每次推送自动运行

v0.6.1.4
· 🗄 **自动备份**：设置 → 数据管理 → 「自动备份」——选一个文件夹后，**每天第一次打开应用时**自动把整库备份成 JSON 写进去，**只保留最近 3 份**（自动清理更旧的，且只删自己写出的文件）；备份可直接用「导入备份」恢复，不联网、不上传
· 备份要能扛住"卸载 / 清除数据"，所以需要先选一次文件夹才生效，并且**故意不写进 App 私有目录**；可随时用「立即备份一次」手动触发、也可关掉开关
· 本次起「更新说明」直接写在 Release 里 —— App 的更新页会把该说明显示出来

v0.6.1.3
· 🔍 **首次引入单元测试**：新增 `app/src/test`（22 个用例）覆盖**历史上反复踩坑的纯逻辑** —— 答案解析（题干回显、圈码泄漏、正文不能被截断）、版本比较与「同版本新构建」判定、四种语言文案表的键/占位符/繁体转换；`tools/run-unit-tests.ps1` 供本机运行，CI 每次推送都会跑
· ⬆️ **更新页增强**：发现新版本时直接显示**该版本的更新说明**（Release 说明，Markdown 已清洗）；新增**「跳过此版本」**（跳过后启动静默检查不再顶红点，可随时取消）；显示**上次检查时间**；检查/下载失败时给出**「重试 / 重新下载」**

v0.6.1.2
· 🌙 **深色主题适配对话区**：解题 / 同类题页面的对话气泡、正文、思考块配色改为 CSS 变量，跟随「应用主题」（浅色 / 深色 / 跟随系统）；此前深色下气泡仍是浅灰、思考块灰字几乎看不清
· 🐞 **崩溃日志（本地）**：闪退时自动记录到本机（版本 / 机型 / 线程 / 完整堆栈，最多留 10 份），设置 → 数据管理可**一键导出 txt / 清空**；**只存本机、不会上传**

v0.6.1.1
· 🔁 **同一版本的新构建也能被发现**：即使版本号没变，只要发布物比本机安装更新、且内容确实不同（SHA-256 不同）就会提示更新（兜底能力，正常发版仍走版本号）
· ℹ️ 「关于 → 应用信息」补上 **MIT License** 一行（灰色小字）

v0.6.1
· ⬆️ **新增「应用更新」页**（设置里，位于「数据管理」与「关于」之间）：显示当前版本、一键**检查更新**、下载并在安装前做**双重校验**（SHA-256 + 签名证书），校验不通过会删除下载的文件
· 🔴 **有新版本时**：「设置」图标与「应用更新」入口显示**红色气泡①**；App 启动时**静默检查**一次，网络失败或已是最新都不打扰你
· ⚙️ **设置结构调整**：「API 管理」并入二级菜单「**AI 管理**」（置于该页**最顶端**），原「AI 配置」随之改名
· ℹ️ **关于页改版**：只保留「应用信息」（版本 / 说明 / **项目主页链接** / Copyright），下方新增「**功能**」说明栏
· 🌐 思考块文案与「功能」栏均跟随界面语言（简体 · 繁體 · English · 日本語 · 한국어）
· 🔁 **同一版本的新构建也能被发现**：即使版本号没变（例如替换了 Release 附件），只要发布物比本机安装更新、且内容确实不同，就会提示更新

v0.6.0
· 🧠 **AI 思考流**：把模型的思考过程用浅灰小字显示在回答气泡**顶端**；思考时**流式逐字增长并自动跟随思路**（用户上滑即取消跟随），思考结束后**自动跳到「思考结尾 + 正式答案开头」**
· 💭 折叠入口有两个：气泡顶端「思考过程」与思考块**底端**的蓝色「折叠思考区 / 展开思考区」（思考很长时不用滚回顶部）；覆盖拍题 / 批改 / 图文提问 / 错题本问答
· ⚙️ AI 配置里的「解题模型」改名为「**解题模式**」（**模型没变**，只是思考开关）：⚡ 快速 = 不思考（也**不渲染思考块**）；🧠 深度思考 = 先推理再作答
· 🐞 修复「**答案生成完就消失**」：删除了「从『分类：/知识点：』一路截断到文末」的清洗规则 —— 模型没写「解答：」时它会把正文整段删掉
· 🐞 修复「**答案里又出现题目**」：剔除回显的元信息行时**容忍序号前缀**（如 `① 题目：…`）
· 🐞 修复解答里「**莫名其妙的④**」：模型会把格式里的 ④ 当段落标签刷在每一段前，现可识别并剔除
· 🛠 解题 / 文字解题 / 批改的提示词统一为「**序号 + 标签**」协议，去掉自相矛盾的重复要求
· 🐞 修复追问时**上一轮答案的思考被本轮思考覆盖**
· 🐞 修复回主页时「停掉在跑的生成 + 清拍摄缓存」因条件写错而静默失效
· 🌐 思考块的文案（思考过程 / 折叠思考区 / 展开思考区）已跟随**界面语言**（简体 · 繁體 · English · 日本語 · 한국어）

v0.5.9
· 📝 题目以文字为主：拍题/图文提问的题干由 AI 忠实转写（公式用 LaTeX、KaTeX 排版），原图默认折叠成「📷 查看原题图片」点击展开；复习仍以原图自测
· ✍️ 新增「AI 配置」页：框选时限 + 自定义要求（Prompt），自定义要求会附加到解题/批改/同类题的每次请求
· 📚 列表预览轻量排版：复习与错题本列表里的题干把 LaTeX 转成 ∫ ∮ ∑ ≤ √ ρ 等可读符号
· 🐞 修复复习刷题崩溃：复习队列原查询 q.* 会连带图片大字段，超出 CursorWindow 2MB 上限
· 🔁 修复复习时同一题连续出现两次：艾宾浩斯第 0 档为 0 天导致今天反复到期，现最短周期为 1 天，今天点过不再出现
· 🖼 修复错题本原图丢失：列表改为轻查询后未补全图片，现进入题目时按 id 补全
· 🎯 修复复习点进去变成查看错题：异步补全图片时覆盖了复习模式
· 🏠 修复复习结束退回桌面：改为显式回主页

v0.5.8
· 🎨 应用主题：新增主题配色——8 套预设色板 + 自定义取色盘（色相/饱和度/明度可调）；设置页「主题」升级为「应用主题」二级页
· 🐞 修复复习刷题崩溃：复习队列原来查询 q.*，把每题的图片大字段一起取出，题目一多就超出 Android CursorWindow 的 2MB 上限而崩溃（表现为点熟悉就退回桌面）；现在列表只取轻字段，进题时才按 id 取图片与会话
· 🔄 修复复习点进去变成查看错题：异步补全图片后覆盖了复习模式标志
· 🏠 修复复习结束退回桌面：刷完最后一题后改为显式回主页

v0.5.7
· 🗂 错题自动归类：AI 判定科目与知识点并自动预选，**优先复用已有科目**（近义/上下位也算）；旧错题打开时自动补齐并写回数据库
· 🧩 解题页多模式统一：拍题 / 批改 / 图文提问 / 错题本共用同一套顶栏与交互；复习返回不再误入拍摄页
· ✏️ 图文提问空会话直接给大输入框（少一跳），标题与会话一致
· 🗑 删除入口并入分类对话框：左下角红色「删除」+ 确认框，删除后留在当前会话
· 🛠 修复：生成中输入栏被顶掉、看不到刚发的图与题干、点通知跳主页、中止后丢气泡、预框选被取消、标签行不能横滑/取消
· 🛠 补充修复：两张模式拍/传第二张后框选页显示错图（现正确显示第二张，第一张的按钮改为「继续上传」）；图文提问只传图片不写字也能存错题本；图文提问提问后标题保持「图文提问」；拍题遇到网络中断（如 503）后不再误显示成图文提问界面，已拍题目保留

v0.5.6
· **📊 新增「学习统计」页**：底栏改为三个页签 —— 📚 学习（原「主页」）/ 📊 统计 / ⚙️ 设置
· 统计内容：今日复习、近 7 天复习、连续天数、待复习；掌握度分布（未开始 / 复习中 / 已掌握）；近 7 天新增错题（可一键切换成**近 1 个月**，带纵坐标刻度）；科目分布
· **Android 17 兼容性修复**：补上精确闹钟权限并在未授予时自动降级（复习提醒不再静默失效）；框选页底部按钮避开手势导航条

v0.5.5
· **菜单调整**：「直接提问」从拍照搜题页搬到**主页**，位于「拍照搜题」与「批改题目」之间（文字/图文直接解答，不必拍照）
· **框选页支持预框选**：进页面即自动框出题目范围（本地算法与 AI 并行，AI 超时回落本地/默认框），可双指缩放图片、长按跳过框选
· **两张模式全程可框选**：拍题 / 批改的「两张」模式，每张都各自框选；框完第二张可按返回回到第一张重框
· **设置页归类**：新增「通用」（API 管理 + AI 框选时限滑条），「个性化」「通知与后台」重新归类
· 回到主页会清空所有拍摄 / 框选缓存

v0.5.4
· ✏️ **批改页重做**：拍完题目（或题目+作答）直接进入**全屏批改页**（复用解题界面，标题「批改」）；批改结果与后续问答**全部流式**，可**带图追问**；生成中「⏸ 中止批改」、追问中「⏸ 中止生成」、空闲「🔄 重新批改」
· 🧩 **练同类题页对齐解题页**：🔄 重新生成（清空重出）、📚 存错题本、⏸ 中止生成；底部同一套输入栏（公式键盘 / 图库 / 附图 / 发送）；「查看答案」为**左下角椭圆按钮**，答案未生成好时置灰
· 🖼 **图库改用系统原生「有序选择」**：勾选时显示 **1/2/3 序号**并按勾选顺序返回；**不再需要相册权限**；两张模式可**一次选两张**（第 1 张=题目，第 2 张=作答）
· 🛡 **多选保护**：**题干**与 **AI 第一条回复**（答案 / 批改结果）**不可选中、不可删除**（灰色虚线圈）；多选态内**禁用长按**，只允许点选
· 💭 **无内容也显示气泡**：流式首字未到时显示「**思考中…**」；中止后**保留气泡**并给蓝色「**继续生成**」
· 📊 **复习页底部改为进度条**；「看解答」时画面保持不动，「收起解答」时回到顶部
· 🔁 拍照搜题页与批改页**各自记住「单张 / 两张」**；↑/↓ 仅在**内容超过一屏**时出现
· 🔤 页面标题统一大字号；修复批改页偶发出现「删除 / 暂不分类」按钮组

v0.5.3
· **解题与追问流式输出**：答案边生成边显示（末尾有 ▍ 光标），长推导不用再整段白等；生成中可继续滚动阅读
· 生成中可点 **⏸ 中止生成**（立即停止）；中断或中止后在内容末尾显示蓝色「**继续生成**」，点击**从中断处接着写**，不重复已写内容
· **长按消息气泡进入多选**：气泡右上角选择圈、选中显示红框；顶栏左侧「全选 / 取消全选」、右侧「删除 / 完成」；多选态下不会触发系统长按选字
· 打开会话**默认停在顶部**；右下角圆形按钮：在顶部显示 **↓**（快速滚到底），否则显示 **↑**（快速回顶），均为平滑滚动
· **同类题页支持流式**：出题只外显题目（答案仍点「查看答案」才展开，未生成好时按钮置灰）；追问同样流式
· 复习：详情页标题显示「**复习 x/xx**」；切下一题时**清屏提示 + 自动折叠答案 + 回到顶部**
· 标题字号统一（解题 / 错题 / 错题本 / 复习均为大字号）；错题本多选改小字号并新增「全选」
· 错题详情顶栏：删除按钮位于分类左侧；生成答案时出现「⏸ 中止生成」且**删除按钮置灰**

v0.5.2
· **公式键盘改为窄条**：默认只显示一行小字「公式键盘」，点一下才展开符号行与 LaTeX 模板行，再点收起——不再占用输入区空间
· 界面**锁定竖屏**：转动手机不再跟着旋转（此前未锁定，横屏下部分页面排版会乱；大屏设备上系统可能仍会忽略该锁定）

v0.5.1
· 错题本：搜索框不再常驻，改为标题栏 **🔍 按钮**唤出；点其他位置（或返回键）自动关闭
· 错题本新增 **「管理」**：科目（分类）纵向列表，可 **✏️ 重命名**、可 **勾选批量删除**；删除前二次确认，并可选择**是否连同科目中的错题一起删除**（不勾选则题目改为「未分类」）

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
v0.6.6.12
· 💾 Harden conversation draft saving and recovery; old-request cleanup no longer affects new drafts, and completed drafts remain until saved to the notebook or history
· 🧹 Explicit learning-data erasure also removes conversation drafts and stops old writes; confirmation now includes history and drafts
· 📖 Distinguish review-save failures from next-question loading failures; retrying after a successful save no longer records another review or advances progress twice
· 🛡️ Read large questions, answers and original images in chunks with short list previews; full-text search, duplicate checks and backups retain complete content
· 🔢 Preserve original Markdown subquestion numbers, including non-sequential numbering
· 🌐 Translate statistics mastery labels with the interface language and complete messages in all four languages

v0.6.6.11
· 🎨 Share title-only cards across Data and AI management; move descriptions into subpages and add a matching card for Technology & compatibility
· ✍️ Give Custom instructions its own AI management subpage, preserving keyboard-aware saving and updating back navigation
· 📝 Restore emoji in v0.6.6.9/v0.6.6.10 changelogs and About feature/privacy items

v0.6.6.10
· ⚙️ Move automatic backups into Backup management; split AI settings into API management, AI cropping and Solving settings with consistent back navigation
· ↩️ Undo question, history or category deletion within 3 seconds before it is committed
· 💬 Preserve old answers until regeneration succeeds; save completed follow-ups promptly and recover interrupted conversations from local drafts
· 📖 Keep the selected review scope, advance only after saving, require confirmation of AI grading suggestions, fix calendar-day calculations and label estimated statistics
· 📥 Import categories and tags only for new notebook questions; clarify rescue export
· 🛡️ Load card images on demand and long conversations in chunks; stop backups exceeding safe memory limits without changing existing data
· 🛠️ Isolate request cancellation; improve reminder intents/scheduling and update cancellation, temporary files and background verification

v0.6.6.9
· 📝 Tighten number cleanup to preserve circled subquestion numbers repeated across conclusions and derivations
· 💾 Open, write and close backup exports in the background, reducing UI stalls and extra memory; keep operations busy until completion
· 💬 Detect AI output limits and interrupted streams, retaining received content for manual continuation or retry without automatic repeat requests
· 🛠️ Remove the local signing hash dependency and clarify release failures without exposing credentials

v0.6.6.8
· 💾 Keep routine saves silent; show backup and operation results in floating Material 3 capsule snackbars without resizing pages, with longer red failure messages
· 🛠 Failed saves remain accessible for retry/export from the snackbar or Data management; report backup export success only after writing the file
· 🖼 Use “View images” for both original-question and follow-up attachments, localized in four languages
· 📝 Request a divider between conclusions and detailed solutions; preserve original subquestion numbers and match each result to its derivation, allowing normal step numbers
· 💬 Short follow-ups and concept explanations need no full solution template; existing answers and DB v10 / Bak v3 remain unchanged

v0.6.6.7
· 💾 Retain recovery copies after save failures; restart recovery, retry and manual export preserve conflicting edits until explicitly discarded
· 🛠 Leaving a notebook entry saves answer content only, preserving current category, tags and review state; resolve pending saves before import or clearing
· 🔒 Remove raw AI response debug logging
· ⬆️ Compare same-version APKs by embedded UTC build time matching their filenames; digest fallback supports legacy APKs and avoids identical-content prompts
· 🧪 Strict JVM tests and isolated emulator data tests on release tags or manual checks; DB v10 / Bak v3 unchanged

v0.6.6.6
· ℹ️ Update About and project documentation: Android 9 minimum, fast/thinking modes share deepseek-flash, and clarify direct AI requests, label analysis scope and local exports
· 💾 Backup failures retain a reason and time in settings; a verified successful write clears the warning. Changing folders resets status, with no new startup dialog
· 📝 Label organization drafts save locally and restore only against matching labels and links; preview is required again before merging. Save failures are shown and drafts can be explicitly discarded
· 🔎 Keep the last 20 local AI analysis diagnostics, with manual export and clearing; only timing, mode, status and counts, without label names, questions, raw responses or keys
· 🧪 Add Android database and file checks in an independent app package and a unified developer check entry; DB v10 / Bak v3 remain unchanged

v0.6.6.5
· 🛠 Updated APK under the same version: correct thinking-mode label analysis parameters that caused immediate HTTP 422 errors; show specific failure reasons and status codes, with version and data formats unchanged
· 🏷 Improved label suggestion parsing: request JSON output, retain valid editable candidates, and distinguish truncated, empty and malformed responses; candidates start unselected and conflicts are still checked before merging
· 🏷 Organize labels with AI suggestions based on the full label list and usage counts; edit source groups and names manually, then preview and confirm merges
· ↩️ Merge and deduplicate links transactionally while preserving problem IDs; undo the latest merge after restart, with conflict checks that preserve later edits
· 🔒 Analysis sends only label IDs, names and question counts, excluding question text, answers and images; it is separate from solve sessions and can be canceled
· 💾 Main DB stays v10; undo uses a separate local auxiliary database. Successful merges replace prior undo; backup stays v3, with undo invalidated before import or clearing data

v0.6.6.4
· 💾 Read back automatic backups and verify SHA-256 before pruning old copies; serialize backup and cleanup, and attempt to remove incomplete new files after failure
· 📥 Read and validate backups in the background with status feedback; decode images per problem to reduce peak memory while preserving transactional rollback
· 🕘 Show failed history saves with a retry action; retain the latest snapshot and original ID without recreating deleted or expired records
· 📌 DB v10 and backup v3 unchanged; no new data migration

v0.6.6.3
· 🛠 Updated APK under the same version: confirm the first successful database upgrade separately from later no-op checks; show DB / Bak and actual schema versions, with an amber warning for legacy data
· 🆔 Permanent problem IDs combine a UUID and first-input timestamp; DB v10 migrates notebook, image, tag and review references after saving a database copy
· 🕘 Create history only on initial photo/text submission; notebook/review opens add no records, and follow-ups, retries and regeneration preserve original ordering
· 💾 Backup v3 deduplicates by permanent ID, preserves local content on repeated imports, and rejects invalid images or references instead of silently merging by text
· ⚠️ v1/v2 backups are no longer accepted. Export a new v3 backup after upgrading; existing notebook data migrates automatically, with missing times inferred from neighboring old IDs

v0.6.6.2
· ℹ️ Credit GPT-6.1 Sol alongside DeepSeek-V4/V4.1 in the About page
· 🕘 Use a consistent timestamp fallback when pruning search history to avoid removing valid records
· 📚 Open linked notebook problems with their latest content, category and tags; view history independently when the linked problem is deleted
· 💾 Isolate save state between problems, save related data in transactions, and append only missing original images
· 🛠 Tie data upgrade prompts to the DB version rather than app patches; show “No upgrade is needed” when nothing needs updating
· 📌 DB v9 and backup v2 are unchanged; old images without a remaining source cannot be recovered, and further manual repair improvements are deferred

v0.6.6.1
· 🧭 **Navigation turned into an explicit page tree**: new `ui/AppNav.kt` (single source of truth for every jump). The solve screen is now told where it was opened from (home / camera / grade / history / notebook / review) and its back button follows that — fixes "open a photo question from **search history**, press back, and land in the **camera**"
· 🔤 **Unified top-bar title size**: settings sub-pages (AI / language / theme / notifications / data / update / about), search history, notebook and review now match the **solve screen** (19sp; the back arrow stays 21sp)
· 🕘 **Search history title no longer has an emoji** (the home button keeps it)
· 📄 Fixed the missing blank line between v0.6.6 and the previous release in the in-app changelog

v0.6.6
· 🗂 Data structure upgrade: multi-image questions are now stored properly (new `question_images` table); **v8 and older data migrate automatically**, with a DB file backup taken before migrating
· 💾 Backup upgrade: now includes **all original images + search history**; import is transactional (rolls back on failure) and **old backups still import**
· 🛠 Data management split into **four sub-pages** (Backup: export / import, Auto backup, Crash logs, Upgrade & Repair); **"Clear all data" moved to the Data management top page** (red danger row, requires typing the confirmation phrase)
· ⚠️ After updating, the first launch warns if legacy data is detected (skippable, no repeat nagging)

v0.6.5.3
· 🐞 **Fixed one of two images still being lost**: the reader used to keep only the main image whenever it existed, while the rest live in the conversation — so a two-photo question showed a single image. The main image and the conversation images are now merged (deduped by base64, max 3) across the notebook detail, review self-test and search history

v0.6.5.2
· 🎛 **Removed the live AI classification when opening a question or the category dialog**: subject and knowledge tags now come only from the presets captured while solving; opening a saved question just reads what is stored, defaulting to "no category + no tags". This also fixes the AI silently rewriting a stored category, and tag checkboxes being hard to untick
· 🖼 **Review now shows every original image** (multi-photo / text+image questions no longer show only the first page, up to 3)
· 🔤 **Unified button size**: the notebook's 🔍 / sort / manage buttons now use the very same size constant as the solve screen's ⏸ stop / 🔄 regenerate buttons (13sp)

v0.6.5.1
· 🐞 **Fixed missing original images after saving to the notebook** (more likely with multi-photo or text+image questions): the images were always in the conversation, just never written to the dedicated image field — saves now include the main image, and older entries are back-filled automatically at launch (notebook cards, review self-test and backups all recover)
· 🔤 **Unified button size**: the 🔍 search / ⇅ sort / manage buttons at the top right of the notebook now match the solve screen's toolbar (12sp → 13sp)
· 🔤 **Unified selection toolbar**: in search history and the solve screen, the select-all / delete / done buttons now use the same size as the solve screen's buttons (the notebook's tighter selection bar stays as it was, by request)

v0.6.5
· 🕘 **New "Search history"** (5th entry on Home): solved questions are kept automatically, so leaving without tapping "Save to notebook" no longer loses them; grouped by day, tap to continue asking, long-press for multi-select delete; the latest 200 entries / 30 days are kept, original images included
· 🐞 **Fixed the crop page's top edge occasionally jumping to the very top**: a "discard if it touches the edge" guard had been written but never wired up — it is now active; the selection is locked as soon as a finger touches down; after a pinch-zoom ends or the image changes the selection is pulled back inside the image; the four corner hit areas gained outer bounds
· 🐞 **Fixed search records being grouped under "Earlier" and even deleted by cleanup**: follow-up / regenerate wrote a zero timestamp, so the record was treated as older than 30 days and removed; the write is fixed and a fallback added
· 🎛 **The custom-instructions Save button now follows the keyboard** (the page stays clean when the keyboard is closed, and the button sits right above the keyboard when it opens); the leftover gap between the keyboard and the input box is gone
· 🏠 Home entries are now laid out as: Solve by photo / Ask with text-image each on its own row → Notebook · Review → Search history (no more "…")

v0.6.4.5
· 📚 **"Save to notebook" now occupies exactly the same slot as "📁 Category"**: it uses the same "📁 + 5 CJK characters" width with left-aligned text and the spare width left blank on the right, so the button group no longer shifts when the two states switch
· ⌨️ **Fixed the custom instructions field being covered by the keyboard**: settings sub-pages now avoid the keyboard (the page moves up when it opens), and the input box has a maximum height with internal scrolling so the caret stays visible

v0.6.4.4
· 🐞 **Fixed "custom instructions never applied"**: the custom prompt in Settings → AI management had **never actually been sent** (the code existed but was never wired up). It is now included when solving, grading, asking for similar questions and following up
· 🐞 **Fixed "the AI could not see your existing categories"**: the prompt sent to the model **always said "known categories: (none)"**, so it invented subjects — which is how duplicates like "大学物理" and "物理" appeared. The cause was a Flow that nobody subscribed to, so it always returned an empty list
· 🐞 **Fixed "some text gets bolded by mistake"**: inputs like `2**10 and 3**5` were rendered as bold spanning unrelated words (fixed in the chat view, the detail page and the list preview)
· 🐞 **Fixed "backing out of a text question returned to the camera screen"**
· 🐞 **Fixed "the thinking bubble could not be selected or deleted after aborting"**
· 📁 **Fixed the category button cutting off the 5th character** (e.g. "信号与系统" showed only the first four): the width is now measured with the button's real type style, with a fallback to the actual label width
· 🔤 **Default prompt cleaned up**: removed redundancy and the self-contradictory "every line must start with a number" rule; asterisks are no longer used for emphasis in the prompt
· 📷 **Removed the "question area auto-selected" hint** on the crop screen: its appearance and disappearance changed the bottom height, which made the picture jump

v0.6.4.3
· 🐞 **Fixed the blank "next problem" page after switching problems in review**: going from a **photo problem** to a **text problem with no original image** made the problem's "light / heavy query redirect" **load it once more** and wipe the review mode along the way → the **Familiar / Vague / Forgot buttons disappeared**, the title went back from "Review x/n" to "Notebook", and it occasionally got **stuck forever on the centred blank "next problem" page**. Review mode is now kept through the whole loading chain, and the clear-screen flag got a fallback reset as well
· 📁 **The "subject" button fixed again**: the **emoji never changes size any more** (it used to shrink together with the subject name); the button is now **wide enough for 5 characters** (the button group on the right therefore shifts left by one character); past 5 characters it **no longer shows "…"** but **shrinks the text** so that as much as possible fits (only a really long name wraps to a second line)
· ↩️ **One back-arrow style for all sub-pages**: now the **21sp large arrow** used on the solve page (the settings sub-pages had lost their text size, so they were too small and, because of the container padding, looked **pushed to the right**); "← Back" on the camera / grading pages was unified as well
· 🔄 **The "App update" page is now kept in sync with the README version log**: patch releases such as `0.x.x.x` **also have their notes written into the in-app "What's new"** (previously README only), and the older four-segment versions were filled in too

v0.6.4.2
· 📁 **The "subject" button has a fixed footprint**: its size is locked to the measured width and height of the "⏸ Stop generation / 🔄 Regenerate" buttons, so with a short subject name (≤4 characters) the emoji no longer shifts and space stays on the right; longer names step the text size down (13 → 12 / 11 / 10 / 9sp), and 9 or more characters may wrap to two lines
· 📷 **One tap on the camera focuses and adjusts exposure**: tap-to-meter now explicitly uses `AF|AE|AWB` and adjusts the exposure compensation within about 1.2 s from preview-frame brightness statistics — it fixes "photographing a computer screen comes out overexposed and unreadable"
· 🔍 **Bigger focus box**: it used to be a hard-coded 42 pixels (only about 12dp on a 3.5× screen), which is far too small; it is now in dp (about 64dp for the whole box)

v0.6.4.1
· 🔤 **Solve-page top bar one more 1sp smaller** (user request): ⏸ Stop generation / 🔄 Regenerate / 📚 Save to notebook are all 14 → **13sp**; the auto-scaling of "📁 subject" steps down to **11 / 10 / 9sp** as well
· 🧠 **Expanding / collapsing the thinking block "remembers your last action"**: once you toggle it by hand, the **whole app, across problems**, follows your last choice (expanded / collapsed), stored as the local preference `think_expand_pref` shared by the solve page and the similar-problems page
· 🐞 **Fixed accidental long-presses on Honor devices**: after tapping the system "dynamic capsule" and then lightly tapping the screen, the app occasionally treated it as a "long-press bubble" and entered multi-select — the web side now resets on `touchcancel` / focus loss and guards the long-press timeout, and the native side resets the gesture state when the window regains focus

v0.6.4
· ⛔ **"Export this problem" has been dropped**: the long-image / PDF export **never managed to include everything** (it still cut off at the bottom after several fixes), so the feature is gone for good — the 📤 button in the solve-page top bar, the export dialog and all related code and strings were **deleted**. To keep a copy, use a system screenshot or copy the question and answer
· ✂️ **New "AI cropping" switch** (Settings → AI management, right below the API usage stats): when it is off, **no AI is called at all** — boxing uses the local algorithm only (faster and no tokens spent), the "AI crop timeout" setting below is hidden, and the manual "AI detect" button on the crop screen is hidden too
· 🔤 **"Stop / Regenerate" show their labels again**: the top bar now reads "⏸ Stop generation" and "🔄 Regenerate" instead of icons only, with the same text size as the other buttons such as "Save to notebook"
· 🔍 **Notebook search button size**: the 🔍 in the title bar now **matches the text size** of "Sort / Manage" next to it

v0.6.3.3
· 🐞 **The real cause of the truncated export found and fixed**: the content height was measured **before the original photo (base64 `<img>`) had finished loading** → the bitmap was shorter than the real content → the bottom got cut off. The page now **waits for both images and fonts** before reporting its height, and **the native side measures once more before capturing and takes the maximum**; the page height calculation now includes the **footer** as well
· 🔤 **Tighter top bar**: the 📤 and ⏸/🔄 icon buttons are explicitly tightened to 44dp wide with 2dp padding (the TextButton minimum width of 58dp makes a single emoji look very "loose"), and the whole group sits against the right edge

v0.6.3.2
· 🐞 **Export truncation fixed again**: it now **shows the export page and copies the real screen pixels band by band with PixelCopy**, no longer relying on offscreen `draw` at all (on a real device that either drew nothing or only the first band); every band must **have content** and **differ from the previous one**, otherwise the band is retried, and if it still fails the app clearly reports "export failed" — no more half screenshots
· 🔤 **Top-bar buttons unified**: "Stop / Regenerate" **keep only their emoji** (⏸ / 🔄); **📤 export, ⏸·🔄 and 📚 save now share one text size**; the "subject" button (shown after saving to the notebook) **still scales with the subject-name length**

v0.6.3.1
· 🐞 **Fixed exports coming out completely blank**: offscreen capture used to lay the page out at **1 pixel wide** (the PNG became a 1px white strip and the PDF came out as 11 nearly empty pages) — the page is now laid out at its real width when mounted, **software rendering** is forced, and the result is checked for "ink"; a blank capture reports a failure instead of saving an empty image
· 📄 **PDF now goes through Chromium printing**: the WebView itself outputs a **vector** PDF paginated to A4 (far sharper than slicing a long image); exports always use a light background (saves ink and looks better)
· 📤 **Export button moved to the left of "Regenerate"** (solve-page top bar); **the solve-page title is 2sp smaller again** (21 → 19)

v0.6.3
· 📤 **New "Export this problem"**: the 📤 button at the far right of the solve-page top bar opens a dialog where you **pick the format (PNG long image / PDF)** and then **choose where to save it** (system "Save to file"). The export contains the original photo, question, answer and subject / key points, with formulas typeset by KaTeX; PDF is paginated to A4 automatically
· 🔠 **New "Text size" setting** (Settings → App theme): Small / Default / Large / Extra large — changes text inside the app only, **UI and conversation together**, system settings untouched
· 🔤 **Overall type scale tightened**: button labels are **1–2 steps smaller**; sub-page titles (notebook / review / solve / similar / settings sub-pages) are **1 step smaller** and sit a little closer to the "←" arrow
· 📚 **Notebook**: the title no longer carries the count (just "Notebook"); the count moved next to the "Search by tag" button and shows the **filtered** number — "N problem(s) in the current list"
· 🩹 Fixed: the on-demand backfill of subject/tags when opening an old problem was occasionally dropped (another classification request was in flight) — it now **retries once automatically**, so old problems get their subject and tags more reliably

v0.6.2
· ✏️ **Redo a review problem by hand**: while reviewing, tap "✏️ Redo by hand" → photograph the solution you wrote on paper → the AI grades it and **updates your mastery from the result** (no need to tap Familiar / Vague / Forgot); jump straight to the next problem, and text-only problems can be redone too
· 📚 **Notebook sorting**: a new "⇅ Sort" menu — recently added / oldest first / next review time / most reviewed / **least mastered first** (your choice is remembered)
· 🔎 **Review filters**: filter by **subject** or show "**weak ones only**"; progress counts the filtered list, and an empty result says so explicitly
· ⚠️ **Duplicate check when saving**: if a problem with the same text already exists you get a "**maybe already saved**" warning showing the existing text, and you can still save it (a hint, never a block; small differences between two transcriptions of the same problem are detected too)
· 🐞 Fixed: **switching the notebook sort jumped to the bottom of the list** (it now returns to the top); switching the review tab or filters does the same
· 🐞 Fixed: **the duplicate check missed the same problem photographed twice** — the AI never transcribes it word-for-word identically (LaTeX marks and punctuation vary), so matching is now "same or highly similar text" (short questions are still compared exactly, to avoid false warnings)
· 🗄 **Automatic backup**: Settings → Data → "Auto backup"; pick a folder and the notebook is backed up to JSON on the **first launch of each day**, keeping only the **last 3** (local only — no network, no upload)
· 🌙 **Dark theme for the conversation area**: bubbles, body text and the thinking block now follow the theme; previously bubbles stayed light grey and the grey thinking text was nearly unreadable in dark mode
· 🐞 **Crash logs (local only)**: crashes are recorded with version / device / full stack trace (last 10 kept) and can be **exported as txt** or cleared from Settings → Data
· ⬆️ **Update page improvements**: the **release notes of the new version are shown in-app**, you can "**skip this version**" (no more red badge for it), the **last check time** is displayed, and failed checks/downloads offer retry
· 🔍 **Engineering**: 47 unit tests added (answer parsing / version comparison / backup pruning / sorting / duplicate check / string tables), run automatically on every push

v0.6.1.4
· 🗄 **Automatic backup**: Settings → Data → "Auto backup" — pick a folder and the whole database is backed up to JSON on the **first launch of each day**, keeping only the **last 3** (older ones are cleaned up automatically, and only files it wrote itself are deleted); the backup can be restored straight from "Import backup", local only — no network, no upload
· A backup has to survive "uninstall / clear data", so a folder must be picked once for this to take effect, and the files are **deliberately not written into the app's private directory**; you can trigger it any time with "Back up now" or switch it off
· From this version on the "what's new" text is written directly in the Release — the app's update page shows it

v0.6.1.3
· 🔍 **Unit tests introduced for the first time**: a new `app/src/test` (22 cases) covering the **pure logic that had bitten us repeatedly** — answer parsing (question echo, circled-number leakage, body text must not be truncated), version comparison and the "new build of the same version" rule, and the keys / placeholders / traditional conversion of the four string tables; run locally with `tools/run-unit-tests.ps1` and by CI on every push
· ⬆️ **Update page improvements**: a new version now shows **its release notes** directly (cleaned-up Markdown); a new "**skip this version**" option (silent startup checks no longer raise a red badge afterwards, and it can be cancelled at any time); the **last check time** is shown; failed checks / downloads offer "**Retry / Download again**"

v0.6.1.2
· 🌙 **Dark theme for the conversation area**: bubbles, body text and the thinking block on the solve / similar-problems pages now use CSS variables and follow "App theme" (light / dark / follow system); previously bubbles stayed light grey and the grey thinking text was nearly unreadable in dark mode
· 🐞 **Crash logs (local)**: crashes are recorded on the device (version / device / thread / full stack trace, last 10 kept), and Settings → Data can **export them as txt or clear them**; **local only — nothing is uploaded**

v0.6.1.1
· 🔁 **New builds of the same version are detected too**: even when the version number is unchanged, an update is offered if the published build is newer than your install and the content really differs (SHA-256 differs) — a safety net, since normal releases still go by version number
· ℹ️ "About → App info" gained a **MIT License** line (small grey text)

v0.6.1
· ⬆️ **New "App update" page** (in Settings, between Data and About): shows the current version, checks for updates, downloads, and **verifies twice before installing** (SHA-256 + signing certificate); a failed check deletes the file
· 🔴 **When an update is available**: a **red badge ①** appears on the Settings icon and on the App update entry; the app also does one **silent check** at startup and stays quiet on failure or when up to date
· ⚙️ **Settings reorganised**: "API" now lives inside the "**AI management**" sub-page (at the very top), and "AI settings" was renamed accordingly
· ℹ️ **About page reworked**: only "App info" (version / description / **project link** / copyright) plus a new "**Features**" section
· 🌐 Thinking-block labels and the Features section follow the UI language (简体 · 繁體 · English · 日本語 · 한국어)
· 🔁 **New builds of the same version are detected too**: even when the version number is unchanged (e.g. a replaced release asset), an update is offered if the published build is newer than your install and the content really differs

v0.6.0
· 🧠 **AI thinking stream**: the model's reasoning is shown in small grey text at the **top** of each answer bubble; while thinking it **streams in and follows along automatically** (scroll up to stop following), and when thinking ends the view **jumps to the end of the reasoning / start of the answer**
· 💭 Two toggle points: the "Thinking" header at the top and the blue "Collapse / Expand thinking" line at the **bottom** of the block (no need to scroll back up when it is long); works for photo solve, grading, ask-with-images and notebook Q&A
· ⚙️ "Solve model" in AI settings is renamed "**Solve mode**" (**the model is unchanged**, it is only the thinking switch): ⚡ Fast = no thinking (and **no thinking block**); 🧠 Deep thinking = reason first, then answer
· 🐞 Fixed **answers disappearing after generation**: removed a cleanup rule that cut everything from "Category / Key points" to the end of the text whenever the model omitted the "Solution:" marker
· 🐞 Fixed **the question being echoed inside the answer**: echoed meta lines are now stripped even when prefixed with a number (e.g. `① 題目：…`)
· 🐞 Fixed **stray ④ markers** in front of each paragraph (the model copied the format marker into the content)
· 🛠 Unified the solve / text-solve / grading prompts on one **numbered-label** protocol and removed contradictory duplicated instructions
· 🐞 Fixed the **previous answer's thinking being overwritten** by the current turn on follow-up questions
· 🐞 Fixed "stop the running generation + clear the capture cache on returning home" silently doing nothing due to a wrong condition
· 🌐 The thinking block labels (Thinking / Collapse thinking / Expand thinking) now follow the **app language** (简体 · 繁體 · English · 日本語 · 한국어)

v0.5.9
· 📝 Text-first questions: for photo/ask the stem is faithfully transcribed by the AI (LaTeX rendered with KaTeX) and the original photo is collapsed behind a View original photo toggle; review still shows the photo for self-testing
· ✍️ New AI settings page: crop timeout plus a custom instructions prompt that is appended to every solve / grading / similar-question request
· 📚 Light typography in list previews: LaTeX in the review and notebook lists is converted to readable symbols such as ∫ ∮ ∑ ≤ √ ρ
· 🐞 Fixed a crash while reviewing: the queue query selected q.* and pulled image blobs, exceeding the 2 MB CursorWindow limit
· 🔁 Fixed the same question appearing twice in a row during review: interval step 0 was 0 days so it stayed due today; the minimum interval is now 1 day, so a question reviewed today will not show up again today
· 🖼 Fixed missing original photos in the notebook: the list now uses a light query and the image is loaded by id when opening a question
· 🎯 Fixed review opening as a plain mistake view: completing the image asynchronously overwrote review mode
· 🏠 Fixed review finishing back to the launcher: it now navigates home explicitly

v0.5.8
· 🎨 App theme: theme colours — 8 preset swatches plus a custom colour picker (hue / saturation / brightness); the settings entry is now an App theme sub-page
· 🐞 Fixed a crash while reviewing: the review queue selected q.*, pulling each question image blob, so a longer queue exceeded the Android 2 MB CursorWindow limit and crashed (it looked like tapping Familiar returned to the home screen); the list now selects light columns only and loads the image/session by id when a question is opened
· 🔄 Fixed review opening as a plain mistake view: completing the image asynchronously overwrote the review-mode flag
· 🏠 Fixed the review finishing back to the launcher: after the last question the app now navigates home explicitly

v0.5.7
· 🗂 Auto-classification: the AI decides the subject and knowledge tags and pre-selects them, **preferring your existing subjects** (near-synonyms and broader/narrower ones count); old questions are back-filled and written to the database
· 🧩 One shared solve screen for photo / grading / text-image ask / notebook; returning from review no longer jumps to the camera
· ✏️ Text-image ask now opens a large input box right away (one hop less), and the title matches the session
· 🗑 Delete moved into the category dialog: red "Delete" at the bottom-left plus a confirm sheet; you stay in the current session
· 🛠 Fixes: input bar pushed away while generating, photo/question not visible while generating, notification jumping home, bubble lost after stopping, pre-selection request cancelled, tag row not scrollable or deselectable
· 🛠 More fixes: in two-shot mode the crop page showed the wrong (first) image after the second shot — now the second image is shown and the first-step button reads "Upload next image"; saving to the notebook works with images and no text; the title stays "Ask with text/image" after asking; a network failure (e.g. 503) while solving no longer looks like the ask screen, and the captured question is kept

v0.5.6
· **📊 New "Learning stats" page**: bottom bar now has three tabs — 📚 Learn (renamed from Home) / 📊 Stats / ⚙️ Settings
· Stats: reviewed today, last 7 days, day streak, due now; mastery split (not started / learning / mastered); mistakes added in the last 7 days (toggle to **30 days**, with a Y axis); subject breakdown
· **Android 17 compatibility fixes**: exact-alarm permission added with graceful fallback (reminders no longer silently stop); crop screen bottom buttons now avoid the gesture bar

v0.5.5
· **Menu change**: "Ask directly" moved from the photo page to the **home screen**, between "Solve by photo" and "Grade my work" (type or attach an image, no photo needed)
· **Pre-selection in the crop screen**: the question area is framed automatically (local algorithm + AI in parallel, falling back to local/default on timeout); pinch to zoom, hold to skip
· **Two-shot mode now crops every image**: each photo goes through cropping; press back on the 2nd to re-crop the 1st
· **Settings reorganized**: new "General" (API, AI crop timeout slider), plus "Personalization" and "Notifications & background"
· Returning to Home clears all capture/crop caches

v0.5.4
· ✏️ **Grading page rebuilt**: shooting the question (or question + your answer) now goes straight to a **full-screen grading page** reusing the solve UI, titled "Grading"; results and follow-ups are **fully streamed** and you can **ask with images** — "⏸ Stop grading" while grading, "⏸ Stop" while asking, "🔄 Re-grade" when idle
· 🧩 **Similar-problem page matches the solve page**: "🔄 Regenerate" (clears and re-generates), "📚 Save to notebook", "⏸ Stop"; the same bottom input bar (formula keys / gallery / images / send); "Show answer" is a **pill button at the bottom-left**, greyed out until the answer is ready
· 🖼 **Gallery now uses the system's ordered selection**: picks are numbered **1/2/3** and returned in tap order; **no photo permission needed**; two-shot mode can pick both at once (1 = question, 2 = your answer)
· 🛡 **Protected messages**: the **question** and the **first AI reply** (answer / grading result) **cannot be selected or deleted** (grey dashed circle); long-press is **disabled inside multi-select**
· 💭 **A bubble even with no content**: "**Thinking…**" until the first token arrives; after a stop the bubble is kept with a blue "**Continue generating**" line
· 📊 **Review bottom row is now a progress bar**; "Show answer" keeps the view still, "Hide answer" scrolls back to the top
· 🔁 Solve and grading pages **each remember single/two-shot mode**; the ↑/↓ button appears **only when the content exceeds one screen**
· 🔤 Page titles unified; fixed a stray "Delete / Uncategorized" button group on the grading page

v0.5.3
· **Streaming answers for solve and follow-up**: the answer appears while it is generated (with a trailing ▍ cursor), so long derivations no longer make you wait for the whole block
· **⏸ Stop** while generating; after a stop or a network drop the text already generated is kept and a blue "**Continue generating**" line appends at the end — tapping it **resumes from where it stopped** without repeating
· **Long-press a message bubble to enter multi-select**: a pick circle at each bubble's top-right and a red border when selected; the bar has "Select all / Deselect all" on the left and "Delete / Done" on the right; system text-selection is suppressed in this mode
· Conversations open **at the top**; the round button at the bottom-right shows **↓** when at the top (scroll to bottom) and **↑** otherwise (back to top), both animated
· **Similar-problem page is streamed too**: the question streams while the answer stays hidden until "Show answer" (the button is disabled until the answer is ready); follow-ups stream as well
· Review: the title shows "**Review x/xx**"; moving to the next question now **clears the screen briefly, collapses the answer and scrolls back to the top**
· Page titles unified to the large size (Solve / Problem / Notebook / Review); the notebook's multi-select title is small and gained a "Select all" button
· Problem detail bar: Delete sits left of the category button; while generating, "⏸ Stop" appears and **Delete is disabled**

v0.5.2
· The **formula keyboard is now a slim strip**: by default it shows a single small "Formula keyboard" line; tap it to expand the symbol and LaTeX template rows, tap again to collapse — it no longer takes up input space
· The UI is now **locked to portrait**: rotating the phone no longer rotates the app (it was unlocked before, and some pages looked broken in landscape; large-screen devices may still ignore this lock)

v0.5.1
· Notebook: the search box no longer takes up space — open it with the **🔍 button** in the title bar; tap anywhere else (or press Back) to close it
· Notebook gained a **"Manage"** entry: a vertical list of categories with **✏️ rename** and **multi-select delete**; deleting asks for confirmation and lets you choose **whether to delete the problems in those categories too** (otherwise they become "Uncategorized")

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
v0.6.6.12
· 💾 会話の下書き保存と復元を改善。古いリクエストの後処理から新しい下書きを保護し、問題集や履歴に保存されるまで完了済みの下書きを保持
· 🧹 学習データの全削除時は会話の下書きと古い書き込みも処理。確認画面に履歴と下書きを明記
· 📖 復習の保存失敗と次の問題の読み込み失敗を区別。保存後の再試行で復習回数や進捗を重複加算しない
· 🛡️ 大きな問題文・解答・原画像を分割読み込みし、一覧は短いプレビューを使用。全文検索・重複確認・バックアップは完全な内容を保持
· 🔢 Markdown の小問番号を維持し、連続していない番号もそのまま表示
· 🌐 統計の習得度ラベルを表示言語に合わせ、4 言語のメッセージを補完

v0.6.6.11
· 🎨 データ管理と AI 管理を同じ見出しカードに統一。説明は子ページへ移し、技術と互換性にも背景を追加
· ✍️ カスタム指示を AI 管理の独立した子ページに分離。キーボード上の保存操作を保持し、戻る経路を更新
· 📝 v0.6.6.9/v0.6.6.10 の更新内容と、機能・プライバシー項目の絵文字を復元

v0.6.6.10
· ⚙️ 自動バックアップをバックアップ管理へ統合。AI 設定を API 管理・AI 切り抜き・解答設定に分け、戻る経路を統一
· ↩️ 問題・履歴・分類の削除は 3 秒以内に取り消し可能。期限後に確定
· 💬 再生成が成功するまで旧回答を保持。追加回答を速やかに保存し、中断した会話は端末内の下書きから復元
· 📖 復習範囲を固定し、保存後に次へ移動。AI 判定は手動確認し、暦日の計算と統計の推定表示を修正
· 📥 新規問題に必要な分類・タグのみ読み込み。救済出力の用途を明確化
· 🛡️ 画像を必要時に取得し、長い会話を分割読み取り。安全なメモリ上限を超えるバックアップは既存データを保持して停止
· 🛠️ リクエストごとの中止、通知の遷移と再設定、更新の取消・一時ファイル・バックグラウンド検証を改善

v0.6.6.9
· 📝 番号の除去条件を絞り、結論と解説に繰り返し現れる丸数字の小問番号を保持
· 💾 バックアップのファイル操作をバックグラウンドで実行し、画面停止と追加メモリを軽減。完了前の重複操作を防止
· 💬 AI の長さ上限と異常終了を識別し、受信内容を保持して手動の続行・再試行を案内。自動再送は行わない
· 🛠️ ローカル署名のハッシュ計算依存を修正し、認証情報を表示せずに公開処理の失敗理由を明確化

v0.6.6.8
· 💾 通常の保存は通知せず、バックアップなどの結果は公式 Material 3 のカプセル型スナックバーで表示。画面を押し下げず、失敗は赤字で長めに表示
· 🛠 未保存内容は通知またはデータ管理から再試行・エクスポート可能。バックアップは実際の書き込み完了後に成功を通知
· 🖼 元の問題と追加質問の添付画像ボタンを「画像を見る」に統一、4言語に対応
· 📝 結論と詳しい解説の間に区切り線を指定。元の小問番号を保ち、結論と導出を対応付け、通常の手順番号も使用可能
· 💬 短い追加質問や概念の説明には解答全体の形式を強制しない。既存の解答、DB v10 / Bak v3 は変更なし

v0.6.6.7
· 💾 保存失敗時のコピーを保持し、再起動後の復元・再試行・手動書き出しに対応。競合を上書きせず、明示的な破棄まで保持
· 🛠 ノート終了時は問題・解答・会話のみ保存し、分類・タグ・復習状態を維持。インポートや全消去前に未保存内容を処理
· 🔒 AI応答原文のデバッグログを削除
· ⬆️ 同一バージョンはAPK内のUTCビルド時刻で比較し、添付名と統一。旧APKはハッシュで判定し、同じ内容を再通知しない
· 🧪 厳密なJVMテストと、リリースタグ・手動確認時の独立エミュレータによるデータテスト。DB v10 / Bak v3は変更なし

v0.6.6.6
· ℹ️ アプリ情報と説明を更新。最低Android 9、高速/思考共にdeepseek-flashを使用し、AIへの直接送信・タグ分析範囲・ローカル出力を明記
· 💾 バックアップ失敗の理由と時刻を設定に保持。書き込みと読み戻し検証に成功すると警告を解除。保存先変更で状態をリセットし、起動時のダイアログは追加なし
· 📝 タグ整理の下書きを端末に自動保存。現在のタグ・関連と一致する場合のみ復元し、統合前に再度プレビューが必要。保存失敗を表示し、明示的に破棄可能
· 🔎 AI 分析の診断を端末に直近20件保持し、手動出力・消去に対応。時間・モード・状態・件数のみで、タグ名・問題・応答原文・キーは含まない
· 🧪 独立パッケージで Android データベース・ファイル検証を追加し、開発チェックを統一。DB v10 / Bak v3 は変更なし

v0.6.6.5
· 🛠 同一バージョンの APK を修正。思考モードのタグ分析で即座に HTTP 422 となるパラメータを修正し、失敗理由と状態コードを表示。バージョンとデータ形式は変更なし
· 🏷 タグ候補の読み取りを修正。JSON 出力を使用し、編集可能な有効候補を保持。途中切れ・空の結果・形式エラーを区別。候補は初期状態で未選択とし、統合前の競合確認は維持
· 🏷 タグ一覧と関連問題数を使った AI 整理候補を追加。統合元・名称を手動編集でき、プレビューと確認後に統合
· ↩️ 問題番号を維持し関連付けをトランザクションで統合・重複排除。再起動後も直前の統合を取り消せ、後の関連編集は競合チェックで保護
· 🔒 分析で送るのはタグ番号・名称・問題数のみ。問題本文・解答・画像は送らず、解題会話と独立してキャンセル可能
· 💾 主 DB は v10 のまま、取消記録は独立した端末内の補助 DB に保存。統合成功後に前回記録を更新。バックアップは v3、インポートや全消去の開始前に取消記録を無効化

v0.6.6.4
· 💾 自動バックアップを再読み取りして SHA-256 を検証後、古いファイルを整理。バックアップと整理を直列化し、失敗時は今回の不完全ファイルの削除を試行
· 📥 バックアップの読み取りと検証をバックグラウンド化し、処理状況を表示。画像を問題ごとに復号し、メモリ使用量を抑えつつ一括ロールバックを維持
· 🕘 履歴の保存失敗を表示し再試行に対応。最新内容と元の番号を保持し、削除済み・期限切れの履歴は再作成しません
· 📌 DB v10・バックアップ v3 は変更なし。追加のデータ移行は不要

v0.6.6.3
· 🛠 同一バージョンの APK を更新。初回の更新成功と以後の更新不要を区別し、DB / Bak と実際の DB バージョンを表示。旧形式データがあれば黄橙色で警告
· 🆔 UUID＋初回入力日時による永続番号を導入。DB v10 へ問題・画像・タグ・復習の関連を移行し、事前に DB のコピーを保存
· 🕘 履歴は撮影・質問の初回送信時のみ作成。ノートや復習から開いても追加せず、追問・再試行・再生成で初回日時や並び順を変更しません
· 💾 バックアップ v3 は永続番号で重複を判定し、再インポート時は端末内の内容を保持。不正な画像や関連は取り込みを中止
· ⚠️ v1/v2 バックアップは非対応。更新後に v3 を再エクスポートしてください。既存の問題は自動移行し、欠けた日時は旧番号の前後から補完

v0.6.6.2
· ℹ️ アプリ情報の開発支援モデルに DeepSeek-V4/V4.1 と併せて GPT-6.1 Sol を記載
· 🕘 検索履歴の期限判定で日時の補完処理を統一し、有効な履歴の誤削除を防止
· 📚 保存済み問題の履歴を開くと最新の内容・分類・タグを表示。元の問題が削除済みなら履歴だけを表示し、自動復元しません
· 💾 問題ごとの保存状態を分離し、関連データをトランザクションで保存。元画像は不足分のみ追加
· 🛠 データ更新の案内は DB バージョンに基づき、小規模更新ごとには繰り返しません。変更不要なら「更新は不要です」と表示
· 📌 DB v9・バックアップ v2 は変更なし。元画像の保存先が残っていない旧問題は復元できず、手動修復の追加改善は保留

v0.6.6.1
· 🧭 **ページ遷移を「ツリー」に整理**：`ui/AppNav.kt`（アプリ全体のページツリー＝唯一のナビゲーション情報源）を追加。解答画面へ入るときは**来た元**（home / camera / grade / history / notebook / review）を渡し、戻るはその元へ —— 「**検索履歴**から写真の問題を開いて戻ると**カメラ**へ飛ぶ」不具合を修正
· 🔤 **上部バーのタイトル文字サイズを統一**：設定のサブページ（AI 管理 / 言語 / テーマ / 通知 / データ管理 / アプリ更新 / このアプリについて）、検索履歴、間違いノート、復習を**解答画面**と同じ 19sp に（戻る矢印は 21sp のまま）
· 🕘 **検索履歴のタイトルから絵文字を削除**（ホームのボタンには残す）
· 📄 アプリ内の更新内容で v0.6.6 と前バージョンの間に空行が 1 行足りないのを修正

v0.6.6
· 🗂 データ構造の更新：複数画像を正式に保存（新テーブル `question_images`）。**v8 以前のデータは自動移行**、移行前に DB ファイルを自動バックアップ
· 💾 バックアップ強化：**全原図＋検索履歴**を含む。インポートはトランザクション化（失敗時ロールバック）、**旧バックアップも読み込み可**
· 🛠 データ管理を**4 つのサブページ**に分割（バックアップ管理=書き出し/読み込み／自動バックアップ／クラッシュログ／データ更新・修復）。**「すべてのデータを消去」はデータ管理の一覧ページへ移動**（赤の危険項目・確認語の入力が必要）
· ⚠️ 更新後の初回起動時に旧形式データを検出すると更新を促します（スキップ可・繰り返し通知なし）

v0.6.5.3
· 🐞 **「2 枚のうち 1 枚が消える」問題を修正**：読み込み側が「メイン画像があればそれだけ使う」実装だったため、残りの画像（会話データ内）が表示されず 1 枚になっていました。メイン画像＋会話内の画像を統合（base64 で重複排除、最大 3 枚）し、間違いノート詳細・復習・検索履歴の 3 か所を修正

v0.6.5.2
· 🎛 **問題を開いた時／分類ダイアログを開いた時の AI リアルタイム判定を廃止**：分類とタグは解答時のプリセットのみを使用し、保存済みの問題では保存されている情報だけを読みます（無ければ「分類なし ＋ タグ未選択」）。AI が保存済みの分類を書き換える問題と、タグのチェックを外しにくい問題も解消
· 🖼 **復習で原図をすべて表示**（複数枚・文字＋画像の問題でも 1 枚目だけにならない、最大 3 枚）
· 🔤 **ボタンの文字サイズを統一**：間違いノートの 🔍 / 並べ替え / 管理 を、解答画面の「⏸ 中止 / 🔄 再生成」と**同じサイズ定数**（13sp）に

v0.6.5.1
· 🐞 **「間違いノートに保存すると原図が消える」問題を修正**（複数枚撮影・文字＋画像で起きやすい）：画像はもともと会話内にあり、専用の画像フィールドに書き込まれていなかっただけです → 保存時に主図を書き込み、起動時に**過去の分も自動補完**（カード・復習・バックアップも復旧）
· 🔤 **ボタンの文字サイズを統一**：間違いノート右上の 🔍 検索 / ⇅ 並べ替え / 管理 を解答画面のツールバーと同じサイズに（12sp → 13sp）
· 🔤 **複数選択ツールバーを統一**：検索履歴と解答画面で、全選択 / 削除 / 完了 を解答画面のボタンと同じサイズに（間違いノートの詰まった選択バーはご要望どおり変更なし）

v0.6.5
· 🕘 **「検索履歴」を追加**（ホームの 5 番目の入口）：解いた問題が自動で残るため、「間違いノートに保存」を押さずに終了しても失われません。日付ごとにグループ化、タップで続けて質問、長押しで複数選択削除。直近 200 件 / 30 日を保持し、元画像も残します
· 🐞 **切り抜き画面で「上辺がたまに最上部へ飛ぶ」問題を修正**：書かれたまま一度も使われていなかった「端に接したら破棄」のガードを有効化。指を置いた瞬間に手動の選択を固定。ピンチ終了時・画像切り替え時に選択枠を画像内へ戻す。四隅の判定に外側の境界を追加
· 🐞 **検索履歴が「それ以前」に分類され、削除までされていた問題を修正**：追問 / 再生成が作成時刻を 0 で書き込み、「30 日超過」とみなされて削除されていました。書き込みを修正し、フォールバックを追加
· 🎛 **カスタム指示の「保存」ボタンがキーボードに追従**（キーボードが閉じているときはページが元のまま、開くとキーボードの真上に表示）。キーボードと入力欄の間の余分な空白も解消
· 🏠 ホームの入口配置を変更：撮影して質問 / 文字・画像で質問 をそれぞれ 1 行 → 間違いノート · 復習 → 検索履歴（「…」は出ません）

v0.6.4.5
· 📚 **「📚 間違いノートに保存」と「📁 分類」の占有幅を完全に一致**：同じく「📁 + 漢字 5 文字」の幅で左揃え、右側は余白 —— 2 つの状態を切り替えても右側のボタン群が動きません
· ⌨️ **カスタム指示の入力欄がキーボードに隠れる問題を修正**：設定の下位ページでキーボードを避けるようにし（開くと内容が上に移動）、入力欄に高さ上限を設けて内部スクロールにしたため、カーソルが常に見えます

v0.6.4.4
· 🐞 **「カスタム指示が効いていなかった」を修正**: 設定 → AI 管理のカスタムプロンプトは**これまで一度も送信されていませんでした**（コードはあるが接続されていなかった）。現在は解答・添削・類似問題・追加質問のすべてで送信されます
· 🐞 **「AI が既存の分類を見られない」を修正**: モデルへ送る文面が**常に「既知の分類：（現在分類なし）」**になっていたため、モデルが科目を勝手に作り、同じ分類が重複していました。原因は誰も購読していない Flow で、値が常に空だったことです
· 🐞 **「意図しない太字」を修正**: `2**10 と 3**5` のような入力が無関係な語をまたいで太字になっていました（チャット表示・詳細ページ・一覧プレビューの 3 か所を修正）
· 🐞 **「文字質問で戻るとカメラ画面に戻ってしまう」を修正**
· 🐞 **「考え中の吹き出しを中止後に選択・削除できない」を修正**
· 📁 **分類ボタンで 5 文字目が切れる問題を修正**（例：「信号与系统」が 4 文字しか表示されない）—— 幅の測定を実際のフォントスタイルに変更
· 🔤 **既定プロンプトの冗長さと矛盾を解消**（「各行に番号必須」と「解答は複数行可」が矛盾していました）。プロンプト内で太字の見本を示すのをやめました
· 📷 切り抜き画面の**「問題範囲を自動選択しました」という案内を削除**（表示の有無で画像の高さが変わり、画面が一瞬ずれていました）

v0.6.4.3
· 🐞 **復習で問題を切り替えたあとの「次の問題」空白ページを修正**：**画像問題**から**原題画像のない文字だけの問題**へ移ると、問題の「軽／重クエリのリダイレクト」が**もう一度読み込み**、そのついでに復習モードまで消していました → **熟悉／模糊／忘記 の 3 ボタンが消える**、タイトルが「復習 x/n」から「誤答ノート」に戻る、ときには**中央の「次の問題」空白ページで永久に止まる**という症状になっていました。現在は読み込み経路のどこでも復習モードが消えず、画面クリアのフラグにも復帰処理を入れました
· 📁 **「分類」ボタンを再修正**：**emoji の文字サイズはいつでも変わりません**（以前は分類名と一緒に縮んでいました）。ボタンは**漢字 5 文字が収まる幅**に拡大（そのぶん右側のボタン群が 1 文字ぶん左へ移動）。5 文字を超えても**「…」は表示せず**、**文字サイズを自動で小さく**してできるだけ全部見せます（どうしても入らないときだけ 2 行）
· ↩️ **すべての下位ページの戻る矢印を統一**：解答ページと同じ **21sp の大きい矢印**に統一（設定の下位ページは文字サイズ指定が抜けていて小さく、余白のせいで**右に寄って**見えていました）。カメラ／添削ページの「← 戻る」も同様に統一
· 🔄 **「アプリ更新」ページを README のバージョン履歴と同期**：`0.x.x.x` のような小さな修正の内容も**アプリ内の「更新内容」に書く**ようにしました（以前は README のみ）。過去の 4 桁バージョンもあわせて補完

v0.6.4.2
· 📁 **「分類」ボタンの占有幅を固定**：「⏸ 生成を中止 / 🔄 再生成」ボタンの実測サイズに合わせ、分類名が短い（4 文字以下）ときは emoji が動かず右側に余白が残ります。名前が長いと文字サイズを段階的に下げ（13 → 12 / 11 / 10 / 9sp）、9 文字以上は 2 行を許可
· 📷 **撮影のシングルタップでフォーカスと露出を同時に調整**：タップ測光で明示的に `AF|AE|AWB` を使い、プレビュー画像の明るさ統計から約 1.2 秒で露出補正を自動調整 —— 「パソコンの画面を撮ると白飛びして読めない」を解消
· 🔍 **フォーカス枠を拡大**：従来は 42 ピクセル固定（3.5× 画面で約 12dp）で小さすぎたため、dp 指定（枠全体で約 64dp）に変更

v0.6.4.1
· 🔤 **解答ページ上部バーの文字をもう 1sp 小さく**（ユーザー要望）：⏸ 生成を中止 / 🔄 再生成 / 📚 誤答ノートに保存 を 14 → **13sp** に統一。「📁 分類」の自動縮小も **11 / 10 / 9sp** に下げました
· 🧠 **思考ブロックの展開／折りたたみが「最後の操作を記憶」**：一度手で切り替えると、**アプリ全体・問題をまたいで**最後の状態（展開／折りたたみ）で表示します（端末内の設定 `think_expand_pref` に保存、解答ページと類似問題ページで共用）
· 🐞 **Honor 端末の誤長押しを修正**：システムの「ダイナミックカプセル」を押したあと画面を軽くタップすると、まれに「長押しバブル」と判定されて複数選択に入っていました —— Web 側に `touchcancel`／フォーカス喪失時のリセットと長押しタイムアウトのガードを追加し、ネイティブ側はウィンドウが再フォーカスされたときにジェスチャ状態をリセットします

v0.6.4
· ⛔ **「この問題を書き出す」機能を廃止**：長い画像 / PDF の書き出しは**どうしても内容が欠けずに収まらない**（何度直しても下部が切れる）ため、この機能は作らないことにしました —— 解答ページ上部の 📤 ボタン、書き出しダイアログ、関連するコードと文言を**すべて削除**しました。保存したい場合は端末のスクリーンショットを使うか、問題文と解答をコピーしてください
· ✂️ **「AI 切り抜き」スイッチを追加**（設定 → AI 管理、「API 使用量」のすぐ下）：オフにすると **AI を一切呼び出さず**、枠取りはローカルアルゴリズムのみ（より速く、トークンも消費しません）。下の「AI 枠取りの制限時間」も非表示になり、切り抜き画面の手動「AI 認識」ボタンも表示されません
· 🔤 **「中止 / 再生成」に文字が戻りました**：上部バーは「⏸ 生成を中止」「🔄 再生成」と表示（アイコンのみではなくなりました）。文字サイズは「誤答ノートに保存」などと同じです
· 🔍 **誤答ノートの検索ボタンのサイズ**：タイトルバーの 🔍 を、右側の「並べ替え / 管理」などと**同じ文字サイズ**に統一

v0.6.3.3
· 🐞 **書き出しが切れる真因を特定して修正**：内容の高さを**原題画像（base64 `<img>`）の読み込みが終わる前**に測っていた → ビットマップが実際より低くなり → 下部が切れていました。現在はページ内で**画像とフォントの両方が揃うのを待って**から高さを報告し、**撮影前にネイティブ側でもう一度測って最大値**を採用。ページ高さの計算に**フッター**も含めました
· 🔤 **上部バーをよりコンパクトに**：📤 と ⏸/🔄 の 2 つのアイコンボタンを 44dp 幅・余白 2dp に明示的に詰めました（TextButton の最小幅 58dp では emoji 1 つがとても「散って」見えるため）。グループ全体を右端に寄せています

v0.6.3.2
· 🐞 **書き出しの切れを再修正**：「**書き出しページを表示して＋PixelCopy で画面の実ピクセルを帯ごとにコピー**」方式に変更し、オフスクリーンの `draw` に一切依存しなくなりました（実機では内容が描かれないか、最初の帯しか描かれませんでした）。各帯は**内容がある**ことと**前の帯と異なる**ことを必須とし、満たさなければその帯を再試行、それでも失敗すれば「書き出し失敗」と明示 —— 中途半端なスクリーンショットは返しません
· 🔤 **上部バーのボタンを統一**：「中止／再生成」は**emoji のみ**（⏸ / 🔄）に。**📤 書き出し、⏸·🔄、📚 誤答ノートに保存 の 3 つは同じ文字サイズ**。「分類」ボタン（誤答ノート保存後に出るもの）は**分類名の長さによる自動縮小を維持**

v0.6.3.1
· 🐞 **書き出しが真っ白になる問題を修正**：以前はオフスクリーン撮影時にページが **1 ピクセル幅**でレイアウトされていました（PNG は 1px の白帯、PDF は 11 ページでほぼ空）。現在はマウント時に実幅でレイアウトし、**ソフトウェアレンダリング**を強制、撮影後に「インクがあるか」を確認。空白なら白い画像を保存せず、明確に失敗を報告します
· 📄 **PDF を Chromium 印刷に変更**：WebView 自身が A4 でページ分割した**ベクター** PDF を出力（「長い画像の分割」よりずっと鮮明）。書き出しは常に明るい背景（省インクで見やすい）
· 📤 **書き出しボタンを「再生成」の左へ移動**（解答ページ上部バー）。**解答ページのタイトルをさらに 2sp 小さく**（21 → 19）

v0.6.3
· 📤 **「この問題を書き出す」を追加**：解答ページ右上の 📤 → ダイアログで**まず形式（PNG 長い画像／PDF）**、次に**保存先**を選択（システムの「ファイルに保存」）。内容は原題の画像＋問題文＋解答＋分類／知識点で、数式は KaTeX で組版。PDF は A4 で自動分割
· 🔠 **「文字サイズ」設定を追加**（設定 → アプリのテーマ）：小／標準／大／特大 —— アプリ内の文字だけを変更し、**画面と会話欄が連動**、端末の設定は変更しません
· 🔤 **全体の文字サイズを調整**：ボタンの文字を**1〜2 段階小さく**、内部ページ（誤答ノート／復習／解答／類似問題／設定の下位ページ）の左上タイトルを**1 段階小さく**し、「←」との間隔も少し詰めました
· 📚 **誤答ノート**：タイトルの件数を外し（「誤答ノート」のみ）、「タグで検索」の行の右側に**絞り込み後の件数**「現在のリストは N 件」を表示
· 🩹 修正：古い問題を開いたときの「分類／タグの追加取得」が、別の分類リクエストと重なると破棄されることがあった → **自動で一度だけ再取得**するようにし、科目とタグがより確実に入るようになりました

v0.6.2
· ✏️ **復習問題を手書きでやり直し**：復習中に「✏️ 手書きでやり直す」→ 紙に書いた解答を撮影 → AI が添削し、**その結果で習熟度を自動更新**（熟悉／模糊／忘記を手で押す必要なし）。そのまま「次の問題」へ進め、文字だけの問題にも対応
· 📚 **誤答ノートの並べ替え**：右上に「⇅ 並べ替え」を追加 —— 新しい順／古い順／次回復習が近い順／復習回数の多い順／**苦手な順**（選択は記憶されます）
· 🔎 **復習のフィルタ**：**科目**で絞り込み、「**苦手だけ**」の表示も可能。進捗は絞り込み後の件数で計算し、該当なしのときはその旨を明示
· ⚠️ **保存時の重複チェック**：同じ問題文が既にある場合は「**既に保存済みかも**」と既存の問題文を表示し、そのまま保存することもできます（あくまで通知。同じ問題の 2 回の転写に細かな違いがあっても検出します）
· 🐞 修正：**誤答ノートの並べ替えを変えるとリスト末尾へ飛んでいた**（現在は先頭に戻ります）。復習のタブ切り替え・フィルタでも同様に先頭へ
· 🐞 修正：**同じ問題を 2 回撮影して保存しても重複と判定されなかった** —— AI の転写は逐字同一にならない（LaTeX 記号や句読点が微妙に異なる）ため、「同一または酷似した問題文」で判定するようにしました（短い問題文は従来どおり完全一致のみ＝誤通知を防ぐ）
· 🗄 **自動バックアップ**：設定 → データ管理 → 「自動バックアップ」。フォルダを選ぶと**毎日最初の起動時**に誤答ノートを JSON へバックアップし、**最新 3 件だけ**保持（端末内のみ・通信なし・送信なし）
· 🌙 **ダークテーマの会話エリア対応**：バブル・本文・思考ブロックの配色がテーマに追従。以前はダークでもバブルが薄いグレーのままで、思考ブロックの灰色文字がほぼ読めませんでした
· 🐞 **クラッシュログ（端末内のみ）**：クラッシュ時にバージョン／機種／スタックトレースを記録（最新 10 件）。設定 → データ管理から **txt 書き出し**や削除ができます
· ⬆️ **更新ページの強化**：新しいバージョンの**更新内容をアプリ内に表示**、「**このバージョンをスキップ**」（以後バッジを出さない）、**前回の確認時刻**の表示、失敗時の再試行
· 🔍 **内部品質**：単体テストを 47 件追加（解答解析／バージョン比較／バックアップ整理／並べ替え／重複判定／文言テーブル）。プッシュのたびに自動実行

v0.6.1.4
· 🗄 **自動バックアップ**：設定 → データ管理 → 「自動バックアップ」——フォルダを選ぶと、**毎日最初にアプリを開いたとき**にデータベース全体を JSON へ書き出し、**最新 3 件だけ**保持（古いものは自動で削除。削除するのは自分が書いたファイルだけ）。バックアップは「バックアップを読み込む」でそのまま復元でき、通信も送信もありません
· 「アンインストール／データ消去」に耐える必要があるため、**あえてアプリ専用領域には書きません**。フォルダを一度選ばないと有効にならず、「今すぐ 1 回バックアップ」でいつでも手動実行でき、スイッチを切ることもできます
· このバージョンから「更新内容」は Release に直接書きます —— アプリの更新ページにその内容が表示されます

v0.6.1.3
· 🔍 **単体テストを初導入**：`app/src/test` を新設（22 件）し、**過去に何度も踏んだ純粋なロジック**をカバー —— 解答解析（問題文の再表示、丸数字の混入、本文が切れないこと）、バージョン比較と「同一バージョンの新ビルド」判定、4 言語の文言テーブルのキー／プレースホルダ／繁体変換。`tools/run-unit-tests.ps1` でローカル実行でき、CI がプッシュのたびに走ります
· ⬆️ **更新ページの強化**：新しいバージョンを見つけると**そのバージョンの更新内容**を表示（Release 説明、Markdown はクリーニング済み）。**「このバージョンをスキップ」**を追加（スキップ後は起動時の静かな確認でバッジが出ず、いつでも解除可能）。**前回の確認時刻**を表示。確認／ダウンロードの失敗時は**「再試行／再ダウンロード」**を提示

v0.6.1.2
· 🌙 **ダークテーマの会話エリア対応**：解答／類似問題ページの会話バブル・本文・思考ブロックの配色を CSS 変数にし、「アプリのテーマ」（ライト／ダーク／システムに従う）に追従。以前はダークでもバブルが薄いグレーのままで、思考ブロックの灰色文字がほぼ読めませんでした
· 🐞 **クラッシュログ（ローカル）**：クラッシュ時に端末内へ記録（バージョン／機種／スレッド／スタックトレース全体、最新 10 件まで）。設定 → データ管理から **txt 書き出し／削除**が可能。**端末内のみで、アップロードはしません**

v0.6.1.1
· 🔁 **同一バージョンの新しいビルドも検出**：バージョン番号が変わっていなくても、公開物がインストール済みより新しく内容が実際に異なる（SHA-256 が異なる）場合に更新を提示します（あくまで保険。通常のリリースはバージョン番号どおり）
· ℹ️ 「このアプリについて → アプリ情報」に **MIT License** の 1 行を追加（小さなグレー文字）

v0.6.1
· ⬆️ **「アプリ更新」ページを追加**（設定内、「データ管理」と「このアプリについて」の間）：現在のバージョン表示・更新の確認・ダウンロード・インストール前の**二重検証**（SHA-256＋署名証明書）。検証に失敗したファイルは削除します
· 🔴 **新しいバージョンがあるとき**：「設定」アイコンと「アプリ更新」項目に**赤いバッジ①**を表示。起動時に**静かに1回確認**し、失敗時や最新時は何も通知しません
· ⚙️ **設定の構成変更**：「API 管理」を「**AI 管理**」サブページの**最上部**に移動し、「AI 設定」の名称も変更
· ℹ️ **「このアプリについて」を刷新**：「アプリ情報」（バージョン／説明／**プロジェクトリンク**／Copyright）と、新設の「**機能**」欄のみ
· 🌐 思考ブロックの文言と「機能」欄は UI 言語に追従（简体 · 繁體 · English · 日本語 · 한국어）
· 🔁 **同一バージョンの新しいビルドも検出**：バージョン番号が同じでも（例：Release の添付を差し替えた場合）、公開物がインストール済みより新しく内容が異なれば更新として提示します

v0.6.0
· 🧠 **AI 思考ストリーム**：モデルの思考過程を回答バブルの**上部**に薄いグレーの小さい文字で表示。思考中は**逐次ストリーミングし自動で追従**（上にスワイプで追従解除）、思考が終わると**「思考の末尾＋解答の先頭」へ自動ジャンプ**
· 💭 折りたたみは2か所：上部の「思考過程」と、ブロック**下部**の青い「思考を折りたたむ／展開する」（長いときも上まで戻らなくてよい）。撮影・添削・画像付き質問・誤答ノートの質疑すべてに対応
· ⚙️ AI 設定の「解答モデル」を「**解答モード**」に改名（**モデルは変わらず**、思考スイッチのみ）：⚡ 高速＝思考なし（思考ブロックも**非表示**）；🧠 深い思考＝先に推論してから回答
· 🐞 **「生成完了後に解答が消える」**不具合を修正：「分類：/知識点：」以降を末尾まで切り捨てるクリーニング規則を削除（モデルが「解答：」を書かないと本文が丸ごと消えていた）
· 🐞 **「解答内に問題文が再表示される」**不具合を修正：番号付きの見出し（例 `① 題目：…`）も除去対象に
· 🐞 各段落の先頭に付く**不要な ④** を修正（書式の記号をモデルが本文へコピーしていた）
· 🛠 解答／文字解答／添削のプロンプトを「**番号＋ラベル**」方式に統一し、矛盾した重複指示を削除
· 🐞 追加質問時に**前の回答の思考が今回の思考で上書きされる**不具合を修正
· 🐞 ホームに戻る際の「生成停止＋撮影キャッシュ削除」が条件ミスで無効だった不具合を修正
· 🌐 思考ブロックの文言（思考プロセス／折りたたむ／展開する）が**アプリの言語**に追従（简体 · 繁體 · English · 日本語 · 한국어）

v0.5.9
· 📝 問題文を文字主体に：撮影/質問では AI が忠実に転写し（LaTeX は KaTeX で描画）、元画像は「元の画像を見る」で折りたたみ。復習は従来どおり画像で自測
· ✍️ AI 設定ページを追加：枠取り制限時間＋カスタム指示（Prompt）。カスタム指示は解答・添削・類似問題の毎回のリクエストに付加
· 📚 一覧プレビューの軽量組版：復習と誤答ノートの一覧で LaTeX を ∫ ∮ ∑ ≤ √ ρ などの読みやすい記号に変換
· 🐞 復習中のクラッシュを修正：キューが q.* を取得し画像 BLOB を読み込み、CursorWindow 2MB 上限を超えていた
· 🔁 復習で同じ問題が連続して出る問題を修正：第 0 段階が 0 日のため当日に再到期していた。最短周期を 1 日にし、今日解いた問題は今日は出ない
· 🖼 誤答ノートで元画像が消える問題を修正：軽量クエリ後に id で画像を補完
· 🎯 復習が「誤答を見る」になる問題を修正：画像の非同期補完が復習モードを上書きしていた
· 🏠 復習終了でランチャーに戻る問題を修正：明示的にホームへ

v0.5.8
· 🎨 アプリテーマ：テーマカラーを追加——8 種類のプリセット＋カスタムカラーピッカー（色相/彩度/明度）；設定のテーマはアプリテーマのサブページに
· 🐞 復習中のクラッシュを修正：復習キューが q.* を取得して各問題の画像 BLOB まで読み込み、問題が増えると Android の CursorWindow 2MB 上限を超えてクラッシュ（熟悉でホームに戻るように見えた）；一覧は軽い列のみ取得し、問題を開くときに id で画像を取得
· 🔄 復習が誤答を見る画面になる問題を修正：画像の非同期補完が復習モードのフラグを上書きしていた
· 🏠 復習終了でランチャーに戻る問題を修正：最後の問題の後は明示的にホームへ

v0.5.7
· 🗂 Auto-classification: the AI picks the subject and knowledge tags, preferring your existing subjects (near-synonyms count); old questions are back-filled and saved to the database
· 🧩 One shared solve screen for photo / grading / text-image ask / notebook; returning from review no longer jumps to the camera
· ✏️ Text/image ask now opens a large input box directly
· 🗑 Delete moved into the category dialog (red Delete at bottom-left + confirm), staying in the session
· 🛠 Fixes: input bar hidden while generating, photo/question missing while generating, notification jumping home, bubble lost after abort, pre-selection request cancelled, tag row not scrollable or deselectable
· 🛠 追加修正：2枚モードで2枚目を撮影/選択した後も枠取り画面が1枚目を表示していた問題（正しく2枚目を表示し、1枚目のボタンは「次の画像を追加」に）；文字なし・画像のみでも誤りノートに保存可能に；質問後もタイトルは「文字/画像で質問」を維持；通信エラー（503 など）時に質問画面と誤認されず、撮影済みの問題も保持

v0.5.6
· **📊 「学習統計」ページを追加**：下部バーを 3 タブに —— 📚 学習（旧「ホーム」）/ 📊 統計 / ⚙️ 設定
· 統計内容：今日の復習、直近 7 日、連続日数、復習待ち；習得度（未着手 / 学習中 / 習得済み）；直近 7 日の追加（**30 日**に切替可・縦軸付き）；科目別
· **Android 17 互換性修正**：正確なアラーム権限を追加し未許可時は自動フォールバック（リマインダーが無効化されない）；切り抜き画面の下部ボタンがジェスチャーバーを避けるように

v0.5.5
· **メニュー変更**：「直接質問」を撮影ページから**ホーム**へ移動（「撮影して質問」と「添削」の間）。文字・画像でそのまま質問できます
· **切り抜き画面に自動プリセレクト**：開くと同時に問題範囲を自動で枠取り（ローカル算法と AI を並行、AI が時間内に返らなければローカル/既定枠にフォールバック）。ピンチで拡大縮小、長押しでスキップ
· **2 枚モードは全枚を切り抜き**：2 枚モードでも 1 枚ずつ枠取り。2 枚目で戻ると 1 枚目を再枠取り
· **設定の整理**：「一般」（API・AI 切り抜き制限時間スライダー）を追加、「パーソナライズ」「通知とバックグラウンド」に再分類
· ホームに戻ると撮影・切り抜きのキャッシュを全消去

v0.5.4
· ✏️ **添削ページを刷新**：撮影後すぐに**全画面の添削ページ**（解答 UI を再利用、タイトル「添削」）へ。結果と以降のやり取りは**すべてストリーミング**、**画像付きの追加質問**も可能。添削中は「⏸ 添削を中止」、質問中は「⏸ 中止」、待機中は「🔄 再添削」
· 🧩 **類似問題ページを解答ページに合わせて刷新**：「🔄 再生成」「📚 ノートに保存」「⏸ 中止」、下部は同じ入力バー（数式キー／ギャラリー／画像／送信）。「解答を見る」は**左下の楕円ボタン**（準備できるまで無効）
· 🖼 **ギャラリーはシステムの「順序付き選択」**：選択に **1/2/3 の番号**が付き、選んだ順で返ります。**写真の権限は不要**。2 枚モードは一度に 2 枚選択可（1=問題、2=答案）
· 🛡 **保護されたメッセージ**：**問題文**と **AI の最初の返信**は**選択・削除できません**（灰色の破線円）。複数選択中は長押し無効
· 💭 **内容が無くても吹き出し**：最初の文字まで「**考え中…**」、中止後は吹き出しを保持し青い「**生成を続ける**」
· 📊 **復習ページ下部は進捗バー**に。「解答を見る」では画面を動かさず、「解答を隠す」で先頭へ
· 🔁 撮影／添削ページはそれぞれ**「1 枚／2 枚」を記憶**。↑/↓ は**内容が 1 画面を超えるときだけ**表示
· 🔤 ページタイトルを統一。添削ページのボタン崩れを修正

v0.5.3
· **解答と追加質問でストリーミング出力に対応**：生成しながら表示（末尾に ▍ カーソル）。長い導出でも一括待ちが不要になりました
· 生成中は **⏸ 中止**が可能。中止・通信断の後は末尾に青い「**生成を続ける**」が出て、タップすると**中断箇所から続きを生成**します（重複しません）
· **メッセージを長押しで複数選択**：各吹き出し右上に選択サークル、選択中は赤枠。バー左に「すべて選択／選択解除」、右に「削除／完了」。この間は OS の長押し文字選択は無効
· 会話を開くと**先頭に表示**。右下の丸ボタンは先頭で **↓**（最下部へ）、それ以外で **↑**（先頭へ）— どちらもスムーズスクロール
· **類似問題ページもストリーミング**：出題は問題文のみ表示（解答は「解答を見る」まで非表示、準備できるまでボタンは無効）。追加質問も同様
· 復習：タイトルが「**復習 x/xx**」に。次の問題へ移ると**画面を一度クリアし、解答を畳んで先頭に戻ります**
· ページタイトルの文字サイズを統一（解答／誤答／間違いノート／復習）。間違いノートの複数選択時は小さめ＋「すべて選択」を追加
· 誤答詳細のバー：削除は分類の左隣。生成中は「⏸ 中止」が出て**削除は無効**になります

v0.5.2
· **数式キーボードを細い帯に変更**：既定では小さく「数式キーボード」と表示するだけ。タップで記号行と LaTeX テンプレート行を展開し、もう一度タップで折りたたみ——入力欄の場所を取りません
· UI を**縦向きに固定**：端末を回しても画面が回転しなくなりました（従来は未固定で、横向きでは一部ページのレイアウトが崩れていました。大画面端末ではシステムがこの固定を無視する場合があります）

v0.5.1
· 間違いノート：検索欄が常時表示されなくなり、タイトルバーの **🔍 ボタン**で開く方式に（他の場所をタップ、または戻るで閉じる）
· 間違いノートに **「管理」** を追加：分類の縦リストで **✏️ 名前変更** と **複数選択して削除** が可能。削除前に確認し、**その分類の問題も一緒に削除するか**を選べます（チェックしない場合は「未分類」になります）

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
v0.6.6.12
· 💾 대화 초안 저장과 복원을 강화. 이전 요청의 정리가 새 초안에 영향을 주지 않으며 오답노트나 기록에 저장될 때까지 완료된 초안을 유지
· 🧹 학습 데이터 전체 삭제 시 대화 초안과 이전 쓰기도 함께 처리. 확인 안내에 검색 기록과 초안을 명시
· 📖 복습 저장 실패와 다음 문제 읽기 실패를 구분. 저장 후 재시도해도 복습 횟수나 진행을 중복 계산하지 않음
· 🛡️ 큰 문제문·답변·원본 이미지를 나누어 읽고 목록에는 짧은 미리보기를 사용. 전체 검색·중복 확인·백업에는 완전한 내용을 유지
· 🔢 연속되지 않은 번호를 포함하여 Markdown 소문항의 원래 번호를 유지
· 🌐 통계 숙달도 이름을 화면 언어에 맞추고 네 언어의 안내를 보완

v0.6.6.11
· 🎨 데이터·AI 관리에 같은 제목 카드를 사용. 설명은 하위 페이지로 옮기고 기술 및 호환성에도 배경 추가
· ✍️ 사용자 지정 지시를 AI 관리의 독립 하위 페이지로 분리. 키보드 위 저장 버튼을 유지하고 뒤로 가기 경로 갱신
· 📝 v0.6.6.9/v0.6.6.10 업데이트 내용과 기능·개인정보 항목의 이모지 복원

v0.6.6.10
· ⚙️ 자동 백업을 백업 관리로 통합. AI 설정을 API 관리·AI 자르기·풀이 설정으로 나누고 뒤로 가기 경로 통일
· ↩️ 문제·기록·분류 삭제 후 3초 안에 실행 취소 가능. 이후 삭제 확정
· 💬 재생성 성공 전까지 이전 답 유지. 후속 응답을 즉시 저장하고 중단된 대화는 기기 내 초안에서 복원
· 📖 선택한 복습 범위를 유지하고 저장 후 다음 문제로 이동. AI 판정은 사용자가 확인하며 날짜 계산과 추정 통계 표시 개선
· 📥 새 문제에 필요한 분류·태그만 가져오기. 복구용 내보내기 용도 명시
· 🛡️ 이미지는 필요할 때 읽고 긴 대화는 나누어 읽기. 안전 메모리 한도를 넘는 백업은 기존 데이터를 유지하며 중단
· 🛠️ 요청별 취소 처리, 알림 이동·예약 및 업데이트 취소·임시 파일·백그라운드 검증 개선

v0.6.6.9
· 📝 번호 정리 조건을 좁혀 결론과 풀이에 반복되는 원형 소문항 번호를 유지
· 💾 백업 파일 열기·쓰기·닫기를 백그라운드에서 처리하여 화면 멈춤과 추가 메모리를 줄이고 완료 전 중복 작업 방지
· 💬 AI 길이 제한과 비정상 종료를 감지하고 받은 내용을 보존하여 수동 계속·재시도를 안내. 자동 재요청은 하지 않음
· 🛠️ 로컬 서명 해시 계산 의존성을 수정하고 자격 증명을 노출하지 않으면서 배포 실패 원인을 명확히 표시

v0.6.6.8
· 💾 일반 저장은 조용히 처리하고 백업 및 작업 결과는 공식 Material 3 캡슐형 스낵바로 표시. 화면을 밀지 않으며 실패는 빨간 글씨로 더 오래 표시
· 🛠 미저장 내용은 스낵바 또는 데이터 관리에서 재시도·내보내기 가능. 백업은 실제 파일 쓰기 완료 후 성공을 알림
· 🖼 원래 문제와 추가 질문의 첨부 이미지 버튼을 “이미지 보기”로 통일하고 4개 언어 지원
· 📝 결론과 상세 풀이 사이에 구분선을 요청. 원래 소문항 번호를 유지하고 결과와 풀이를 대응시키며 일반 단계 번호도 허용
· 💬 짧은 추가 질문과 개념 설명에는 전체 풀이 형식을 강제하지 않음. 기존 답변과 DB v10 / Bak v3 유지

v0.6.6.7
· 💾 저장 실패 시 복구 사본을 보관하고 재시작 복구·재시도·수동 내보내기를 지원. 충돌하는 편집은 덮어쓰지 않으며 명시적으로 버릴 때만 사본 삭제
· 🛠 오답 화면 종료 시 문제·답변·대화만 저장하여 현재 분류·태그·복습 상태 유지. 가져오기·전체 삭제 전에 미저장 내용 처리
· 🔒 AI 응답 원문 디버그 로그 제거
· ⬆️ 같은 버전은 APK에 내장된 UTC 빌드 시각으로 비교하고 파일명과 통일. 기존 APK는 해시로 판단하며 같은 내용은 다시 알리지 않음
· 🧪 엄격한 JVM 테스트 및 릴리스 태그·수동 검사 시 독립 에뮬레이터 데이터 테스트. DB v10 / Bak v3 유지

v0.6.6.6
· ℹ️ 앱 정보와 설명 갱신: 최소 Android 9, 빠른/사고 모드의 deepseek-flash 공유 및 AI 직접 전송·태그 분석 범위·로컬 내보내기를 명확히 안내
· 💾 백업 실패 원인과 시간을 설정에 유지하고 쓰기·읽기 검증 성공 후 경고를 해제. 폴더 변경 시 상태를 초기화하며 시작 팝업은 추가하지 않음
· 📝 태그 정리 초안을 로컬에 자동 저장하고 현재 태그·연결과 일치할 때만 복원. 병합 전 미리보기를 다시 실행해야 하며 저장 실패 표시 및 명시적 초안 폐기 지원
· 🔎 최근 20회 AI 분석 진단을 로컬에 보관하고 수동 내보내기·지우기 지원. 시간·모드·상태·수만 기록하며 태그 이름·문제·응답 원문·키는 제외
· 🧪 독립 패키지의 Android 데이터베이스·파일 검사와 통합 개발 검사 추가. DB v10 / Bak v3 유지, 주 DB 마이그레이션 없음

v0.6.6.5
· 🛠 같은 버전의 APK 수정: 사고 모드 태그 분석에서 즉시 HTTP 422가 발생하는 매개변수를 수정하고 실패 원인·상태 코드를 표시. 버전과 데이터 형식은 유지
· 🏷 태그 제안 읽기 개선: JSON 출력을 요청하고 편집 가능한 유효 후보를 유지하며 잘림·빈 결과·형식 오류를 구분. 후보는 기본 미선택이며 병합 전 충돌 검사는 유지
· 🏷 전체 태그 목록과 연결 문제 수를 바탕으로 AI 정리 제안. 원본 그룹과 이름을 직접 수정하고 미리 보기·확인 후 병합
· ↩️ 문제 번호를 유지하며 연결을 트랜잭션으로 병합·중복 제거. 재시작 후 마지막 병합 취소 지원, 이후 관련 편집은 충돌 검사로 보존
· 🔒 분석 시 태그 번호·이름·문제 수만 전송하며 문제 본문·답변·이미지는 제외. 풀이 대화와 별개로 실행하고 취소 가능
· 💾 주 DB는 v10 유지, 취소 기록은 별도 기기 내 보조 DB에 저장. 병합 성공 후 이전 기록 교체. 백업은 v3 유지, 가져오기나 전체 삭제 시작 전에 취소 기록 무효화

v0.6.6.4
· 💾 자동 백업을 다시 읽어 SHA-256 검증 후 이전 파일 정리. 백업과 정리를 순차 실행하고 실패 시 이번에 만든 불완전 파일 삭제 시도
· 📥 백업 읽기와 검증을 백그라운드에서 실행하고 상태 표시. 이미지를 문제별로 해독해 최대 메모리 사용을 줄이며 전체 트랜잭션 롤백 유지
· 🕘 검색 기록 저장 실패 알림과 재시도 지원. 최신 내용과 원래 번호를 유지하며 삭제되거나 만료된 기록은 다시 만들지 않음
· 📌 DB v10 및 백업 v3 유지. 추가 데이터 이전 없음

v0.6.6.3
· 🛠 같은 버전의 APK 교체: 첫 데이터베이스 업그레이드 성공과 이후 변경 불필요를 구분하고 DB / Bak 및 실제 DB 버전 표시. 이전 형식 데이터가 있으면 황색 경고 표시
· 🆔 UUID와 최초 입력 시간으로 영구 문제 번호 도입. DB 복사본을 먼저 저장한 뒤 문제·이미지·태그·복습 연결을 DB v10으로 이전
· 🕘 촬영 또는 질문을 처음 제출할 때만 기록 생성. 오답 노트·복습에서 열어도 추가하지 않으며 후속 질문·재시도·재생성으로 최초 시간과 정렬이 바뀌지 않음
· 💾 백업 v3는 영구 번호로 중복을 판정하고 재가져오기 시 기기 내 내용을 유지하며, 잘못된 이미지나 연결이 있으면 가져오기 중단
· ⚠️ v1/v2 백업은 지원하지 않습니다. 업데이트 후 v3로 다시 내보내세요. 기존 오답은 자동 이전하며 누락된 시간은 이전 숫자 번호의 앞뒤 문제를 기준으로 보완

v0.6.6.2
· ℹ️ 앱 정보에 기존 DeepSeek-V4/V4.1과 함께 GPT-6.1 Sol 개발 지원 표기 추가
· 🕘 검색 기록 정리 시 날짜 대체 처리를 통일하여 유효한 기록이 잘못 삭제되는 문제 방지
· 📚 오답 노트에 저장된 기록은 최신 내용·분류·태그로 열며, 원래 문제가 삭제된 경우 기록만 독립적으로 표시
· 💾 문제별 저장 상태를 분리하고 관련 데이터를 트랜잭션으로 저장하며, 원본 이미지는 누락된 항목만 추가
· 🛠 데이터 업그레이드 안내는 DB 버전을 기준으로 하여 소규모 업데이트마다 반복하지 않으며, 변경이 없으면 업그레이드가 필요 없다고 표시
· 📌 DB v9와 백업 v2는 유지. 원본이 남아 있지 않은 이전 문제의 이미지는 복구할 수 없으며 수동 복구의 추가 개선은 보류

v0.6.6.1
· 🧭 **페이지 이동을 트리로 정리**: `ui/AppNav.kt`(앱 전체 페이지 트리 = 유일한 내비게이션 정보원) 추가. 풀이 화면에 들어갈 때 **어디서 왔는지**(home / camera / grade / history / notebook / review)를 함께 넘기고 뒤로 가기는 그곳으로 — 「**검색 기록**에서 사진 문제를 열고 뒤로 가면 **카메라**로 가던」버그 수정
· 🔤 **상단 바 제목 글자 크기 통일**: 설정 하위 페이지(AI 관리 / 언어 / 테마 / 알림 / 데이터 관리 / 앱 업데이트 / 정보), 검색 기록, 오답 노트, 복습을 **풀이 화면**과 같은 19sp로 (뒤로 가기 화살표는 21sp 유지)
· 🕘 **검색 기록 상단 제목에서 이모지 제거**(홈 버튼에는 유지)
· 📄 앱 내 업데이트 내용에서 v0.6.6과 이전 버전 사이에 빈 줄이 하나 빠진 것을 수정

v0.6.6
· 🗂 데이터 구조 업그레이드: 여러 이미지를 정식으로 저장(새 테이블 `question_images`). **v8 이전 데이터는 자동 이전**, 이전 전에 DB 파일 자동 백업
· 💾 백업 개선: **모든 원본 이미지 + 검색 기록** 포함. 가져오기는 트랜잭션(실패 시 롤백), **예전 백업도 가져올 수 있음**
· 🛠 데이터 관리를 **4개 하위 페이지**로 분리(백업 관리=내보내기/가져오기, 자동 백업, 크래시 로그, 데이터 업그레이드·복구). **「모든 데이터 삭제」는 데이터 관리 목록 페이지로 이동**(빨간 위험 항목, 확인 문구 입력 필요)
· ⚠️ 업데이트 후 첫 실행 시 구형식 데이터가 감지되면 업그레이드를 안내합니다(건너뛰기 가능, 반복 알림 없음)

v0.6.5.3
· 🐞 **「두 장 중 한 장이 사라지던」문제 수정**: 읽기 쪽이 「대표 이미지가 있으면 그것만 사용」하도록 되어 있어 나머지 이미지(대화 데이터에 있음)가 표시되지 않았습니다. 대표 이미지 + 대화 내 이미지를 병합(중복 제거, 최대 3장)해 오답 노트 상세·복습·검색 기록 3곳을 함께 수정

v0.6.5.2
· 🎛 **문제를 열거나 분류 대화상자를 열 때의 AI 실시간 판정 제거**: 분류와 태그는 풀이 시의 프리셋만 사용하며, 저장된 문제는 저장된 정보만 읽습니다(없으면 「분류 없음 + 태그 미선택」). AI가 저장된 분류를 바꾸던 문제와 태그 체크 해제가 잘 안 되던 문제도 함께 해결
· 🖼 **복습에서 원본 이미지를 모두 표시**(여러 장·글+이미지 문제도 첫 장만 보이지 않음, 최대 3장)
· 🔤 **버튼 글자 크기 통일**: 오답 노트의 🔍 / 정렬 / 관리 버튼을 풀이 화면의 「⏸ 중지 / 🔄 다시 생성」과 **같은 크기 상수**(13sp)로

v0.6.5.1
· 🐞 **「오답 노트에 저장하면 원본 이미지가 사라지던」문제 수정**(여러 장 촬영·글+이미지에서 더 자주 발생): 이미지는 원래 대화 안에 있었고 전용 이미지 필드에 기록되지 않았을 뿐입니다 → 저장 시 대표 이미지를 기록하고, 실행 시 **기존 항목도 자동 복구**(카드·복습·백업 모두 복원)
· 🔤 **버튼 글자 크기 통일**: 오답 노트 오른쪽 위의 🔍 검색 / ⇅ 정렬 / 관리 버튼을 풀이 화면 툴바와 같은 크기로(12sp → 13sp)
· 🔤 **다중 선택 툴바 통일**: 검색 기록과 풀이 화면에서 전체 선택 / 삭제 / 완료를 풀이 화면 버튼과 같은 크기로 (오답 노트의 빽빽한 선택 바는 요청대로 그대로)

v0.6.5
· 🕘 **「검색 기록」 추가**(홈의 다섯 번째 항목): 푼 문제가 자동으로 남아 「오답 노트에 저장」을 누르지 않고 나가도 사라지지 않습니다. 날짜별 그룹, 탭하면 이어서 질문, 길게 눌러 여러 개 삭제. 최근 200건 / 30일 보관하며 원본 이미지도 남깁니다
· 🐞 **자르기 화면에서 「위쪽 테두리가 가끔 맨 위로 튀는」 문제 수정**: 작성만 되고 한 번도 쓰이지 않던 「가장자리에 닿으면 버림」 가드를 활성화했습니다. 손가락을 대는 순간 수동 선택이 고정됩니다. 핀치 종료·이미지 전환 시 선택 영역을 이미지 안으로 되돌립니다. 네 모서리 판정에 바깥 경계를 추가
· 🐞 **검색 기록이 「그 이전」으로 분류되고 삭제까지 되던 문제 수정**: 추가 질문 / 재생성이 생성 시각을 0으로 기록해 「30일 초과」로 판단되어 삭제되었습니다. 기록을 바로잡고 폴백을 추가
· 🎛 **사용자 지정 지시의 「저장」 버튼이 키보드를 따라 표시**됩니다(키보드가 닫혀 있으면 페이지가 그대로, 열면 키보드 바로 위). 키보드와 입력란 사이의 불필요한 여백도 제거
· 🏠 홈 항목 배치 변경: 사진으로 질문 / 글·이미지 질문 각각 한 줄 → 오답 노트 · 복습 → 검색 기록 (「…」 없음)

v0.6.4.5
· 📚 **「📚 오답노트에 저장」과 「📁 분류」의 자리를 완전히 일치**: 같은 「📁 + 한자 5자」 너비에 왼쪽 정렬, 오른쪽은 여백 —— 두 상태를 전환해도 오른쪽 버튼 그룹이 움직이지 않습니다
· ⌨️ **사용자 지정 지시 입력란이 키보드에 가려지던 문제 수정**: 설정 하위 페이지가 키보드를 피하도록 했고(키보드가 열리면 내용이 위로 이동), 입력란에 높이 상한을 두어 내부 스크롤되므로 커서가 항상 보입니다

v0.6.4.4
· 🐞 **「사용자 지정 지시가 적용되지 않던 문제」수정**: 설정 → AI 관리의 사용자 지정 프롬프트가 **지금까지 한 번도 전송되지 않았습니다**(코드만 있고 연결되어 있지 않았음). 이제 풀이·채점·유사 문제·추가 질문 모두에 포함됩니다
· 🐞 **「AI가 기존 분류를 보지 못하던 문제」수정**: 모델에 보내는 문구가 **항상 「알려진 분류: (현재 분류 없음)」**이어서 모델이 과목을 지어냈고, 같은 분류가 중복 생성되었습니다. 원인은 아무도 구독하지 않는 Flow라 값이 늘 빈 목록이었던 것입니다
· 🐞 **「엉뚱한 글자가 굵게 나오던 문제」수정**: `2**10 과 3**5` 같은 입력이 관련 없는 단어까지 굵게 렌더링되었습니다(대화 화면·상세 페이지·목록 미리보기 3곳 수정)
· 🐞 **「텍스트 질문에서 뒤로 가면 카메라 화면으로 돌아가던 문제」수정**
· 🐞 **「생각 중 말풍선을 중단 후 선택·삭제할 수 없던 문제」수정**
· 📁 **분류 버튼에서 5번째 글자가 잘리던 문제 수정**(예: 「信号与系统」가 4글자만 표시) — 너비 측정을 실제 글꼴 스타일로 변경
· 🔤 **기본 프롬프트의 중복과 모순 정리**(「모든 줄에 번호 필수」와 「해답은 여러 줄 가능」이 모순). 프롬프트에서 별표로 굵게 표시하는 예시를 제거했습니다
· 📷 자르기 화면의 **「문제 영역을 자동 선택했습니다」 안내 삭제**(표시 여부에 따라 이미지 높이가 바뀌어 화면이 흔들렸습니다)

v0.6.4.3
· 🐞 **복습에서 문제를 바꾼 뒤 「다음 문제」 빈 페이지 수정**: **이미지 문제**에서 **원본 사진이 없는 텍스트 문제**로 넘어갈 때 문제의 「경량 / 중량 조회 리디렉션」이 **한 번 더 로드**되면서 복습 모드를 함께 지웠습니다 → **익숙 / 애매 / 모름 세 버튼이 사라지고**, 제목이 「복습 x/n」에서 「오답노트」로 돌아가며, 간혹 **가운데 「다음 문제」 빈 페이지에서 영구히 멈추는** 증상이 있었습니다. 이제 복습 모드는 로딩 경로 전체에서 지워지지 않고, 화면 초기화 플래그에도 복구 처리를 넣었습니다
· 📁 **「분류」 버튼 재수정**: **emoji 글자 크기는 언제나 그대로**입니다(이전에는 분류 이름과 함께 줄어들었습니다). 버튼을 **한자 5자가 들어가는 너비**로 넓혔고(그만큼 오른쪽 버튼 그룹이 한 글자만큼 왼쪽으로 이동), 5자를 넘으면 **「…」를 표시하지 않고** **글자 크기를 자동으로 줄여** 최대한 다 보여줍니다(정말 안 들어갈 때만 두 줄)
· ↩️ **모든 하위 페이지의 뒤로 화살표 스타일 통일**: 풀이 화면과 같은 **21sp 큰 화살표**로 통일(설정 하위 페이지는 글자 크기 지정이 빠져 작았고, 여백 때문에 **오른쪽으로 치우쳐** 보였습니다). 카메라 / 첨삭 페이지의 「← 뒤로」도 함께 통일
· 🔄 **「앱 업데이트」 페이지를 README 버전 기록과 동기화**: `0.x.x.x` 같은 작은 수정의 내용도 **앱 안 「업데이트 내역」에 함께 기록**합니다(이전에는 README에만). 지난 4자리 버전도 모두 채웠습니다

v0.6.4.2
· 📁 **「분류」 버튼 자리 고정**: 크기를 「⏸ 생성 중단 / 🔄 다시 생성」 버튼의 실측 너비·높이로 고정해서, 분류 이름이 짧을(4자 이하) 때 emoji가 움직이지 않고 오른쪽에 여백이 남습니다. 이름이 길어지면 글자 크기를 단계적으로 낮추고(13 → 12 / 11 / 10 / 9sp), 9자 이상은 두 줄까지 허용
· 📷 **촬영 한 번 누르면 초점과 노출을 함께 조정**: 탭 측광에 `AF|AE|AWB`를 명시하고, 미리보기 프레임 밝기 통계로 약 1.2초 안에 노출 보정을 자동 조정 —— 「컴퓨터 화면을 찍으면 하얗게 날아가 글자가 안 보임」 해결
· 🔍 **초점 상자 확대**: 기존에는 42픽셀 고정(3.5× 화면에서 약 12dp)이라 너무 작았습니다. dp 기준(상자 전체 약 64dp)으로 변경

v0.6.4.1
· 🔤 **풀이 화면 상단 글자 1sp 더 작게**(사용자 요청): ⏸ 생성 중단 / 🔄 다시 생성 / 📚 오답노트에 저장 을 14 → **13sp** 로 통일. 「📁 분류」의 자동 축소도 **11 / 10 / 9sp** 로 낮췄습니다
· 🧠 **사고 영역 펼치기 / 접기에 「마지막 조작 기억」**: 한 번 직접 바꾸면 **앱 전체, 문제를 넘어서** 마지막 상태(펼침 / 접힘)로 표시합니다(기기 내 설정 `think_expand_pref` 에 저장, 풀이 화면과 유사 문제 화면이 함께 사용)
· 🐞 **Honor 기기 오터치 롱프레스 수정**: 시스템 「다이내믹 캡슐」을 누른 뒤 화면을 살짝 탭하면 간혹 「롱프레스 말풍선」으로 판정되어 다중 선택에 들어갔습니다 —— 웹 쪽에 `touchcancel` / 포커스 해제 시 초기화와 롱프레스 타임아웃 가드를 추가하고, 네이티브 쪽은 창이 다시 포커스될 때 제스처 상태를 초기화합니다

v0.6.4
· ⛔ **'이 문제 내보내기' 기능을 없앴습니다**: 긴 이미지 / PDF 내보내기는 **아무리 고쳐도 내용이 잘리지 않게 담을 수 없어서** 이 기능은 만들지 않기로 했습니다 — 문제 풀이 화면 상단의 📤 버튼, 내보내기 대화상자, 관련 코드와 문구를 **모두 삭제**했습니다. 보관이 필요하면 시스템 스크린샷을 쓰거나 문제와 풀이를 복사하세요
· ✂️ **'AI 자르기' 스위치 추가**(설정 → AI 관리, 'API 사용량' 바로 아래): 끄면 **AI를 전혀 호출하지 않고** 자르기는 로컬 알고리즘만 사용합니다(더 빠르고 토큰도 소모하지 않음). 아래 'AI 자르기 제한 시간'도 숨겨지고, 자르기 화면의 수동 'AI 인식' 버튼도 표시되지 않습니다
· 🔤 **'중단 / 다시 생성'에 글자가 돌아왔습니다**: 상단에 '⏸ 생성 중단', '🔄 다시 생성'으로 표시됩니다(아이콘만 표시하지 않음). 글자 크기는 '오답노트에 저장' 등과 같습니다
· 🔍 **오답노트 검색 버튼 크기**: 제목 표시줄의 🔍 를 오른쪽 '정렬 / 관리' 등과 **같은 글자 크기**로 통일

v0.6.3.3
· 🐞 **내보내기 잘림의 진짜 원인을 찾아 수정**: 내용 높이를 **원본 문제 이미지(base64 `<img>`)가 다 로드되기 전**에 측정했습니다 → 비트맵이 실제 내용보다 낮아 → 아래가 잘렸습니다. 이제 페이지에서 **이미지와 글꼴이 모두 준비된 뒤** 높이를 보고하고, **캡처 전에 네이티브에서 한 번 더 측정해 최댓값**을 사용합니다. 페이지 높이 계산에 **바닥글**도 포함
· 🔤 **상단 바를 더 촘촘하게**: 📤 와 ⏸/🔄 두 아이콘 버튼을 44dp 너비, 안쪽 여백 2dp로 명시적으로 좁혔습니다(TextButton 기본 최소 너비 58dp 때문에 emoji 하나가 매우 「흩어져」 보였습니다). 그룹 전체를 오른쪽 끝에 붙였습니다

v0.6.3.2
· 🐞 **내보내기 잘림 재수정**: 「**내보내기 페이지를 표시한 뒤 + PixelCopy 로 화면의 실제 픽셀을 띠 단위로 복사**」 방식으로 바꿔, 오프스크린 `draw` 에 전혀 의존하지 않습니다(실기기에서는 내용이 아예 안 그려지거나 첫 띠만 그려졌습니다). 각 띠는 **내용이 있을 것**과 **이전 띠와 다를 것**을 요구하고, 아니면 그 띠를 재시도하며 그래도 실패하면 「내보내기 실패」로 분명히 알립니다 —— 반쪽 스크린샷은 주지 않습니다
· 🔤 **상단 바 버튼 통일**: 「중단 / 다시 생성」은 **emoji 만**(⏸ / 🔄) 남깁니다. **📤 내보내기, ⏸·🔄, 📚 오답노트에 저장 세 버튼의 글자 크기가 같고**, 「분류」 버튼(오답노트 저장 후 나타남)은 **분류 이름 길이에 따른 자동 축소를 유지**

v0.6.3.1
· 🐞 **내보내기가 전부 빈 화면이던 문제 수정**: 이전에는 오프스크린 캡처 시 페이지가 **1픽셀 너비**로 배치되었습니다(PNG는 1px 흰 줄, PDF는 11쪽이 거의 빈 상태). 이제 마운트할 때 실제 너비로 배치하고 **소프트웨어 렌더링**을 강제하며, 캡처 후 「잉크가 있는지」 확인해서 빈 화면이면 흰 이미지를 저장하지 않고 실패를 분명히 알립니다
· 📄 **PDF를 Chromium 인쇄로 변경**: WebView가 직접 A4로 나눈 **벡터** PDF를 출력합니다(「긴 이미지 자르기」보다 훨씬 선명). 내보내기는 항상 밝은 배경(잉크 절약, 보기 좋음)
· 📤 **내보내기 버튼을 「다시 생성」 왼쪽으로 이동**(풀이 화면 상단). **풀이 화면 제목을 2sp 더 작게**(21 → 19)

v0.6.3
· 📤 **「이 문제 내보내기」 추가**: 풀이 화면 오른쪽 위 📤 → 대화상자에서 **먼저 형식(PNG 긴 이미지 / PDF)** 을 고르고, 그다음 **저장 위치**를 선택합니다(시스템 "파일로 저장"). 내용은 원본 사진 + 문제 + 풀이 + 분류 / 지식 태그이며 수식은 KaTeX로 조판됩니다. PDF는 A4로 자동 분할
· 🔠 **「글자 크기」 설정 추가**(설정 → 앱 테마): 작게 / 기본 / 크게 / 아주 크게 — 앱 안의 글자만 바꾸고 **화면과 대화 영역이 함께** 바뀝니다. 기기 설정은 건드리지 않습니다
· 🔤 **전체 글자 크기 정리**: 버튼 글자를 **1~2단계 작게**, 내부 페이지(오답노트 / 복습 / 풀이 / 유사 문제 / 설정 하위 페이지)의 왼쪽 위 제목을 **1단계 작게** 하고 "←"와의 간격도 조금 줄였습니다
· 📚 **오답노트**: 제목에서 개수를 빼고("오답노트"만), "태그로 검색" 줄 오른쪽에 **필터된 개수** "현재 목록 N개"를 표시합니다
· 🩹 수정: 예전 문제를 열 때의 "분류/태그 추가 채우기"가 다른 분류 요청과 겹치면 버려지던 문제 → **자동으로 한 번 더 시도**하도록 해서 과목과 태그가 더 안정적으로 채워집니다

v0.6.2
· ✏️ **복습 문제를 손으로 다시 풀기**：복습 중 「✏️ 손으로 다시 풀기」→ 종이에 쓴 풀이를 촬영 → AI가 채점하고 **그 결과로 숙련도를 자동 갱신**(익숙/애매/모름을 직접 누를 필요 없음). 바로 「다음 문제」로 넘어갈 수 있고, 텍스트만 있는 문제도 가능
· 📚 **오답노트 정렬**：오른쪽 위에 「⇅ 정렬」 추가 —— 최근 추가순 / 오래된 순 / 다음 복습이 빠른 순 / 복습 횟수 많은 순 / **취약한 순**(선택은 기억됩니다)
· 🔎 **복습 필터**：**과목**으로 거르거나 「**취약한 것만**」 표시 가능. 진행률은 필터된 개수로 계산하고, 결과가 없으면 그렇게 알려줍니다
· ⚠️ **저장 시 중복 확인**: 같은 문제 지문이 이미 있으면 「**이미 저장했을 수 있습니다**」와 기존 지문을 보여주고, 그래도 저장할 수 있습니다(알림일 뿐 차단하지 않음. 같은 문제를 두 번 옮겨 적은 미세한 차이도 감지)
· 🐞 수정: **오답노트 정렬을 바꾸면 목록 맨 아래로 튀던 문제** (이제 맨 위로 돌아갑니다). 복습 탭 전환이나 필터 변경도 동일
· 🐞 수정: **같은 문제를 두 번 촬영해 저장해도 중복으로 잡히지 않던 문제** —— AI 전사는 글자 단위로 같지 않으므로(LaTeX 기호·구두점 차이) 이제 「지문이 같거나 매우 유사」로 판정합니다(짧은 지문은 기존대로 완전 일치만 = 오탐 방지)
· 🗄 **자동 백업**：설정 → 데이터 관리 → 「자동 백업」. 폴더를 선택하면 **매일 처음 실행할 때** 오답노트를 JSON으로 백업하고 **최근 3개만** 보관(기기 내에만 저장·네트워크 없음·업로드 없음)
· 🌙 **다크 테마 대화 영역 지원**：말풍선·본문·사고 블록 색상이 테마를 따릅니다. 이전에는 다크에서도 말풍선이 밝은 회색이고 사고 블록의 회색 글씨가 거의 보이지 않았습니다
· 🐞 **크래시 로그(기기 내에만)**：크래시 시 버전/기기/전체 스택을 기록(최근 10개). 설정 → 데이터 관리에서 **txt로 내보내기** 또는 삭제 가능
· ⬆️ **업데이트 페이지 개선**：새 버전의 **업데이트 내용을 앱에서 바로 표시**, 「**이 버전 건너뛰기**」(이후 배지 표시 안 함), **마지막 확인 시간** 표시, 실패 시 다시 시도
· 🔍 **내부 품질**：단위 테스트 47개 추가(답안 파싱/버전 비교/백업 정리/정렬/중복 판정/문구 테이블). 푸시할 때마다 자동 실행

v0.6.1.4
· 🗄 **자동 백업**: 설정 → 데이터 관리 → 「자동 백업」 —— 폴더를 선택하면 **매일 처음 앱을 열 때** 데이터베이스 전체를 JSON으로 백업하고 **최근 3개만** 보관합니다(오래된 것은 자동 정리, 자기가 쓴 파일만 삭제). 백업은 「백업 가져오기」로 바로 복원할 수 있고, 네트워크도 업로드도 없습니다
· 백업은 「삭제 / 데이터 지우기」를 견뎌야 하므로 **일부러 앱 전용 폴더에 쓰지 않습니다**. 폴더를 한 번 선택해야 적용되고, 「지금 한 번 백업」으로 언제든 수동 실행하거나 스위치를 끌 수 있습니다
· 이 버전부터 「업데이트 안내」는 Release에 직접 씁니다 —— 앱의 업데이트 페이지가 그 내용을 표시합니다

v0.6.1.3
· 🔍 **단위 테스트 첫 도입**: `app/src/test` 를 새로 만들고 22개 케이스로 **그동안 반복해서 걸렸던 순수 로직**을 검증합니다 —— 답안 파싱(문제 지문 재표시, 원문자 누출, 본문이 잘리지 않을 것), 버전 비교와 「같은 버전 새 빌드」 판정, 4개 언어 문구 테이블의 키 / 자리표시자 / 번체 변환. `tools/run-unit-tests.ps1` 로 로컬 실행하고 CI가 푸시마다 돌립니다
· ⬆️ **업데이트 페이지 개선**: 새 버전을 찾으면 **그 버전의 업데이트 안내**를 바로 표시(Release 설명, Markdown 정리 완료). **「이 버전 건너뛰기」** 추가(건너뛰면 시작 시 조용한 확인에서 배지가 뜨지 않고 언제든 해제 가능). **마지막 확인 시간** 표시. 확인 / 다운로드 실패 시 **「다시 시도 / 다시 다운로드」** 제공

v0.6.1.2
· 🌙 **다크 테마 대화 영역 지원**: 풀이 / 유사 문제 화면의 말풍선, 본문, 사고 블록 색상을 CSS 변수로 바꿔 「앱 테마」(라이트 / 다크 / 시스템 따름)를 따릅니다. 이전에는 다크에서도 말풍선이 밝은 회색이고 사고 블록의 회색 글씨가 거의 보이지 않았습니다
· 🐞 **크래시 로그(로컬)**: 크래시 시 기기에 기록합니다(버전 / 기기 / 스레드 / 전체 스택, 최근 10개까지). 설정 → 데이터 관리에서 **txt로 내보내기 / 비우기** 가능하며 **기기에만 저장되고 업로드되지 않습니다**

v0.6.1.1
· 🔁 **같은 버전의 새 빌드도 감지**: 버전 번호가 그대로여도 게시물이 설치본보다 새롭고 내용이 실제로 다르면(SHA-256 다름) 업데이트를 안내합니다(안전장치이며, 일반 릴리스는 버전 번호를 따릅니다)
· ℹ️ 「정보 → 앱 정보」에 **MIT License** 한 줄 추가(작은 회색 글씨)

v0.6.1
· ⬆️ **「앱 업데이트」 페이지 추가**(설정에서 「데이터 관리」와 「정보」 사이): 현재 버전 표시·업데이트 확인·다운로드·설치 전 **이중 검증**(SHA-256 + 서명 인증서). 검증 실패 시 파일을 삭제합니다
· 🔴 **새 버전이 있을 때**: 「설정」 아이콘과 「앱 업데이트」 항목에 **빨간 배지①** 표시. 앱 시작 시 **조용히 한 번 확인**하며 실패하거나 최신이면 아무 알림도 하지 않습니다
· ⚙️ **설정 구조 변경**: 「API 관리」를 「**AI 관리**」 하위 페이지 **맨 위**로 이동하고 「AI 설정」 이름도 변경
· ℹ️ **「정보」 페이지 개편**: 「앱 정보」(버전 / 설명 / **프로젝트 링크** / Copyright)와 새로 추가한 「**기능**」 항목만 표시
· 🌐 사고 과정 문구와 「기능」 항목이 UI 언어를 따릅니다 (简体 · 繁體 · English · 日本語 · 한국어)
· 🔁 **같은 버전의 새 빌드도 감지**: 버전 번호가 같아도(예: 릴리스 첨부 교체) 게시물이 설치본보다 최신이고 내용이 실제로 다르면 업데이트로 안내합니다

v0.6.0
· 🧠 **AI 사고 과정 스트림**: 모델의 사고 과정을 답변 말풍선 **상단**에 연한 회색 작은 글씨로 표시합니다. 생각하는 동안 **실시간으로 늘어나며 자동으로 따라가고**(위로 스크롤하면 추적 해제), 사고가 끝나면 **「사고 끝 + 답변 시작」으로 자동 이동**합니다
· 💭 접는 지점이 두 곳: 상단의 「사고 과정」과 블록 **하단**의 파란 「사고 과정 접기/펼치기」(길 때 위로 올라갈 필요 없음). 촬영·첨삭·이미지 질문·오답노트 질의 모두 지원
· ⚙️ AI 설정의 「풀이 모델」을 「**풀이 모드**」로 변경(**모델은 그대로**, 사고 스위치만): ⚡ 빠름 = 사고 없음(사고 블록도 **표시 안 함**); 🧠 깊은 사고 = 먼저 추론한 뒤 답변
· 🐞 **「생성 완료 후 답변이 사라지는」** 문제 수정: 「분류:/지식점:」부터 끝까지 잘라내던 정리 규칙 삭제(모델이 「解答:」를 쓰지 않으면 본문이 통째로 지워졌음)
· 🐞 **「답변에 문제가 다시 나오는」** 문제 수정: 번호가 붙은 메타 줄(예 `① 題目：…`)도 제거
· 🐞 문단마다 붙던 **불필요한 ④** 수정(모델이 형식 기호를 본문에 복사했음)
· 🛠 풀이/텍스트 풀이/첨삭 프롬프트를 「**번호 + 라벨**」 방식으로 통일하고 모순된 중복 지시 삭제
· 🐞 추가 질문 시 **이전 답변의 사고가 이번 사고로 덮어써지던** 문제 수정
· 🐞 홈으로 돌아갈 때 「생성 중지 + 촬영 캐시 삭제」가 조건 오류로 동작하지 않던 문제 수정
· 🌐 사고 과정 블록의 문구(사고 과정 / 접기 / 펼치기)가 **앱 언어**를 따릅니다 (简体 · 繁體 · English · 日本語 · 한국어)

v0.5.9
· 📝 문제를 텍스트 중심으로: 촬영/질문 시 AI가 충실히 전사하고(LaTeX는 KaTeX로 렌더링) 원본 사진은 원본 이미지 보기로 접어 둡니다. 복습은 기존처럼 사진으로 자체 점검
· ✍️ AI 설정 페이지 추가: 자르기 제한 시간 + 사용자 지정 지시(Prompt). 사용자 지정 지시는 풀이·첨삭·유사 문제의 모든 요청에 추가됩니다
· 📚 목록 미리보기 경량 조판: 복습/오답노트 목록의 LaTeX를 ∫ ∮ ∑ ≤ √ ρ 등 읽기 쉬운 기호로 변환
· 🐞 복습 중 크래시 수정: 큐가 q.*를 조회해 이미지 BLOB까지 읽어 CursorWindow 2MB 한도를 초과했음
· 🔁 복습에서 같은 문제가 연속으로 나오던 문제 수정: 0단계가 0일이라 당일 재도래했음. 최단 주기를 1일로 하여 오늘 푼 문제는 오늘 나오지 않음
· 🖼 오답노트에서 원본 사진이 사라지던 문제 수정: 경량 조회 후 id로 이미지를 보완
· 🎯 복습이 오답 보기로 열리던 문제 수정: 이미지 비동기 보완이 복습 모드를 덮어썼음
· 🏠 복습 종료 시 런처로 돌아가던 문제 수정: 명시적으로 홈으로 이동

v0.5.8
· 🎨 앱 테마: 테마 색상 — 8가지 프리셋 + 사용자 지정 색상 선택기(색상/채도/밝기); 설정의 테마가 앱 테마 하위 페이지로
· 🐞 복습 중 크래시 수정: 복습 큐가 q.*를 조회해 각 문제의 이미지 BLOB까지 읽어, 문제가 많아지면 Android CursorWindow 2MB 한도를 넘어 크래시(熟悉를 누르면 홈으로 가는 것처럼 보임); 이제 목록은 가벼운 컬럼만 조회하고 문제를 열 때 id로 이미지를 가져옴
· 🔄 복습이 오답 보기로 열리는 문제 수정: 이미지를 비동기로 채우면서 복습 모드 플래그를 덮어썼음
· 🏠 복습 종료 시 런처로 돌아가는 문제 수정: 마지막 문제 후 명시적으로 홈으로 이동

v0.5.7
· 🗂 자동 분류: AI가 과목과 지식 태그를 판단해 자동 선택하며 **기존 과목을 우선**합니다(유사어·상하위 포함); 오래된 문제는 자동으로 채워 DB에 저장됩니다
· 🧩 촬영 / 첨삭 / 글·이미지 질문 / 오답노트가 하나의 화면을 공유합니다; 복습에서 돌아갈 때 카메라로 가지 않습니다
· ✏️ 글·이미지 질문은 빈 세션이면 바로 큰 입력창을 보여줍니다(한 단계 감소), 제목도 세션과 일치합니다
· 🗑 삭제를 분류 대화상자로 이동했습니다: 왼쪽 아래 빨간 "삭제" + 확인창, 삭제 후에도 현재 세션에 머뭅니다
· 🛠 수정: 생성 중 입력창이 밀려남, 생성 중 사진·문제가 안 보임, 알림이 홈으로 이동, 중단 후 말풍선 소실, 사전 선택 요청 취소됨, 태그 줄 가로 스크롤·해제 불가
· 🛠 추가 수정: 2장 모드에서 두 번째 사진을 찍거나 선택한 뒤에도 자르기 화면이 첫 번째 사진을 보여주던 문제(이제 두 번째를 표시하고 첫 단계 버튼은 "다음 이미지 추가"); 글 없이 이미지만으로도 오답노트 저장 가능; 질문 후에도 제목이 "글/이미지 질문" 유지; 네트워크 오류(503 등) 때 질문 화면으로 오인되지 않고 촬영한 문제도 유지

v0.5.6
· **📊 "학습 통계" 페이지 추가**: 하단 바가 3개 탭으로 —— 📚 학습(기존 홈) / 📊 통계 / ⚙️ 설정
· 통계: 오늘 복습, 최근 7일, 연속 일수, 복습 대기; 숙달도(미시작/학습 중/숙달); 최근 7일 추가(**30일** 전환 가능, 세로축 포함); 과목별
· **Android 17 호환성 수정**: 정확한 알람 권한 추가 및 미허용 시 자동 대체(알림이 조용히 멈추지 않음); 자르기 화면 하단 버튼이 제스처 바를 피하도록

v0.5.5
· **메뉴 변경**: "직접 질문"을 촬영 페이지에서 **홈**으로 이동("사진으로 질문"과 "첨삭" 사이). 글/이미지로 바로 질문할 수 있습니다
· **자르기 화면 자동 사전 선택**: 화면을 열면 문제 영역을 자동으로 잡아 줍니다(로컬 알고리즘과 AI 병렬, AI가 시간 내 응답하지 않으면 로컬/기본 박스로 대체). 두 손가락 확대/축소, 길게 눌러 건너뛰기
· **2장 모드도 모든 사진을 자르기**: 2장 모드에서도 한 장씩 잘라내며, 2번째에서 뒤로 가면 1번째를 다시 자릅니다
· **설정 정리**: 「일반」(API, AI 자르기 제한 시간 슬라이더) 추가, 「개인 설정」「알림 및 백그라운드」로 재분류
· 홈으로 돌아가면 촬영·자르기 캐시를 모두 비웁니다

v0.5.4
· ✏️ **첨삭 페이지 개편**: 촬영 후 바로 **전체 화면 첨삭 페이지**(풀이 UI 재사용, 제목 "첨삭")로 이동. 결과와 이후 대화는 **모두 스트리밍**, **이미지가 있는 추가 질문** 가능. 첨삭 중 "⏸ 첨삭 중단", 질문 중 "⏸ 중단", 대기 중 "🔄 다시 첨삭"
· 🧩 **유사 문제 페이지를 풀이 페이지에 맞춰 개편**: "🔄 다시 생성", "📚 오답 노트에 저장", "⏸ 중단", 하단은 동일한 입력 바(수식 키/갤러리/이미지/전송). "정답 보기"는 **왼쪽 아래 타원 버튼**(준비 전에는 비활성)
· 🖼 **갤러리는 시스템 "순서 선택"**: 선택 시 **1/2/3 번호**가 표시되고 고른 순서대로 반환됩니다. **사진 권한 불필요**. 2장 모드는 한 번에 2장 선택(1=문제, 2=답안)
· 🛡 **보호된 메시지**: **문제 본문**과 **AI의 첫 응답**은 **선택·삭제할 수 없습니다**(회색 점선 원). 다중 선택 중에는 길게 누르기가 비활성
· 💭 **내용이 없어도 말풍선**: 첫 글자까지 "**생각 중…**", 중단 후에는 말풍선을 유지하고 파란 "**생성 계속**"
· 📊 **복습 페이지 하단은 진행 바**로. "정답 보기"는 화면을 움직이지 않고, "정답 숨기기"는 맨 위로
· 🔁 촬영/첨삭 페이지는 각각 **"1장/2장"을 기억**. ↑/↓는 **내용이 한 화면을 넘을 때만** 표시
· 🔤 페이지 제목 통일. 첨삭 페이지 버튼 깨짐 수정

v0.5.3
· **풀이와 추가 질문에 스트리밍 출력 지원**: 생성되는 대로 표시(끝에 ▍ 커서). 긴 유도 과정도 통째로 기다릴 필요가 없습니다
· 생성 중 **⏸ 중단** 가능. 중단·네트워크 끊김 후에는 끝에 파란 "**생성 계속**"이 생기고, 누르면 **중단 지점부터 이어서** 생성합니다(중복 없음)
· **메시지를 길게 눌러 다중 선택**: 말풍선 오른쪽 위에 선택 원, 선택 시 빨간 테두리. 상단 왼쪽 "전체 선택/선택 해제", 오른쪽 "삭제/완료". 이 모드에서는 OS 길게 누르기 문자 선택이 비활성화됩니다
· 대화를 열면 **맨 위에서 시작**. 오른쪽 아래 원형 버튼은 맨 위에서 **↓**(맨 아래로), 그 외에는 **↑**(맨 위로) — 모두 부드러운 스크롤
· **유사 문제 페이지도 스트리밍**: 문제만 표시되고 정답은 "정답 보기" 전까지 숨김(준비 전에는 버튼 비활성). 추가 질문도 스트리밍
· 복습: 제목이 "**복습 x/xx**"로 표시. 다음 문제로 넘어가면 **화면을 잠시 비우고 정답을 접은 뒤 맨 위로** 돌아갑니다
· 페이지 제목 글자 크기 통일(풀이/오답/오답 노트/복습). 오답 노트 다중 선택 시에는 작은 글자 + "전체 선택" 추가
· 오답 상세 바: 삭제는 분류 왼쪽. 생성 중에는 "⏸ 중단"이 나타나고 **삭제는 비활성**됩니다

v0.5.2
· **수식 키보드를 얇은 띠로 변경**: 기본적으로 작게 '수식 키보드'만 표시하고, 탭하면 기호 줄과 LaTeX 템플릿 줄이 펼쳐지며 다시 탭하면 접힙니다 — 입력 영역을 차지하지 않습니다
· UI를 **세로 방향으로 고정**: 기기를 돌려도 화면이 회전하지 않습니다(이전에는 고정되지 않아 가로 방향에서 일부 페이지 배치가 깨졌습니다. 대형 화면 기기에서는 시스템이 이 고정을 무시할 수 있습니다)

v0.5.1
· 오답 노트: 검색창이 항상 자리를 차지하지 않고, 제목 표시줄의 **🔍 버튼**으로 엽니다(다른 곳을 탭하거나 뒤로 가면 닫힘)
· 오답 노트에 **'관리'** 추가: 분류 세로 목록에서 **✏️ 이름 변경**과 **다중 선택 삭제**가 가능합니다. 삭제 전 확인하며, **해당 분류의 문제도 함께 삭제할지** 선택할 수 있습니다(선택하지 않으면 '미분류'로 바뀝니다)

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
