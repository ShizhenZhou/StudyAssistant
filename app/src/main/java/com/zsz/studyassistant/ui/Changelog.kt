package com.zsz.studyassistant.ui

/**
 * 版本更新内容（与项目 README 的「版本记录」保持一致）。
 * 支持 4 种语言；繁體中文由简体经 s2t() 自动转换（见 L10n.kt 的映射表）。
 */
internal val CHANGELOG_ZH: String = """
v0.6.2
· ✏️ **复习可「手写重做」**：复习题目时点「✏️ 手写重做」→ 拍下你在纸上写的解答 → AI 批改并**按批改结果自动更新掌握度**（不用再手点熟悉/模糊/忘记）；批改完可直接「下一题」，纯文字题也能重做
· 📚 **错题本排序**：右上角新增「⇅ 排序」——最近添加 / 最早添加 / 下次复习时间 / 复习次数 / **最不熟优先**（选择会记住）
· 🔎 **复习筛选**：复习页可按**科目**筛选，也可「**只看不熟**」；进度按筛选后的题数算，筛空时会明确提示
· ⚠️ **存题查重**：题干相同的题在保存前会提示「**可能已存过**」并给出已有题干，可「仍然保存」（只提示、不阻止）
· 🗄 **自动备份**：设置 → 数据管理 → 「自动备份」，选一个文件夹后**每天第一次打开应用**自动把错题本备份成 JSON，**只保留最近 3 份**（只存本机、不联网、不上传）
· 🌙 **深色主题适配对话区**：深色下气泡 / 正文 / 思考块配色跟随主题；此前深色下气泡仍是浅灰、思考块灰字几乎看不清
· 🐞 **崩溃日志（只存本机）**：闪退时自动记录版本 / 机型 / 完整堆栈（最多留 10 份），设置 → 数据管理里可一键**导出 txt** 或清空
· ⬆️ **更新页增强**：查到新版本时**直接显示该版本的更新说明**、可「**跳过此版本**」（之后不再顶红点）、显示**上次检查时间**、检查/下载失败可「重试 / 重新下载」
· 🔍 **内部质量**：新增 47 个单元测试（答案解析 / 版本比较 / 备份裁剪 / 排序 / 查重 / 文案表），每次推送自动运行

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
v0.6.2
· ✏️ **Redo a review problem by hand**: while reviewing, tap "✏️ Redo by hand" → photograph the solution you wrote on paper → the AI grades it and **updates your mastery from the result** (no need to tap Familiar / Vague / Forgot); jump straight to the next problem, and text-only problems can be redone too
· 📚 **Notebook sorting**: a new "⇅ Sort" menu — recently added / oldest first / next review time / most reviewed / **least mastered first** (your choice is remembered)
· 🔎 **Review filters**: filter by **subject** or show "**weak ones only**"; progress counts the filtered list, and an empty result says so explicitly
· ⚠️ **Duplicate check when saving**: if a problem with the same text already exists you get a "**maybe already saved**" warning showing the existing text, and you can still save it (a hint, never a block)
· 🗄 **Automatic backup**: Settings → Data → "Auto backup"; pick a folder and the notebook is backed up to JSON on the **first launch of each day**, keeping only the **last 3** (local only — no network, no upload)
· 🌙 **Dark theme for the conversation area**: bubbles, body text and the thinking block now follow the theme; previously bubbles stayed light grey and the grey thinking text was nearly unreadable in dark mode
· 🐞 **Crash logs (local only)**: crashes are recorded with version / device / full stack trace (last 10 kept) and can be **exported as txt** or cleared from Settings → Data
· ⬆️ **Update page improvements**: the **release notes of the new version are shown in-app**, you can "**skip this version**" (no more red badge for it), the **last check time** is displayed, and failed checks/downloads offer retry
· 🔍 **Engineering**: 47 unit tests added (answer parsing / version comparison / backup pruning / sorting / duplicate check / string tables), run automatically on every push

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
v0.6.2
· ✏️ **復習問題を手書きでやり直し**：復習中に「✏️ 手書きでやり直す」→ 紙に書いた解答を撮影 → AI が添削し、**その結果で習熟度を自動更新**（熟悉／模糊／忘記を手で押す必要なし）。そのまま「次の問題」へ進め、文字だけの問題にも対応
· 📚 **誤答ノートの並べ替え**：右上に「⇅ 並べ替え」を追加 —— 新しい順／古い順／次回復習が近い順／復習回数の多い順／**苦手な順**（選択は記憶されます）
· 🔎 **復習のフィルタ**：**科目**で絞り込み、「**苦手だけ**」の表示も可能。進捗は絞り込み後の件数で計算し、該当なしのときはその旨を明示
· ⚠️ **保存時の重複チェック**：同じ問題文が既にある場合は「**既に保存済みかも**」と既存の問題文を表示し、そのまま保存することもできます（あくまで通知）
· 🗄 **自動バックアップ**：設定 → データ管理 → 「自動バックアップ」。フォルダを選ぶと**毎日最初の起動時**に誤答ノートを JSON へバックアップし、**最新 3 件だけ**保持（端末内のみ・通信なし・送信なし）
· 🌙 **ダークテーマの会話エリア対応**：バブル・本文・思考ブロックの配色がテーマに追従。以前はダークでもバブルが薄いグレーのままで、思考ブロックの灰色文字がほぼ読めませんでした
· 🐞 **クラッシュログ（端末内のみ）**：クラッシュ時にバージョン／機種／スタックトレースを記録（最新 10 件）。設定 → データ管理から **txt 書き出し**や削除ができます
· ⬆️ **更新ページの強化**：新しいバージョンの**更新内容をアプリ内に表示**、「**このバージョンをスキップ**」（以後バッジを出さない）、**前回の確認時刻**の表示、失敗時の再試行
· 🔍 **内部品質**：単体テストを 47 件追加（解答解析／バージョン比較／バックアップ整理／並べ替え／重複判定／文言テーブル）。プッシュのたびに自動実行

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
v0.6.2
· ✏️ **복습 문제를 손으로 다시 풀기**：복습 중 「✏️ 손으로 다시 풀기」→ 종이에 쓴 풀이를 촬영 → AI가 채점하고 **그 결과로 숙련도를 자동 갱신**(익숙/애매/모름을 직접 누를 필요 없음). 바로 「다음 문제」로 넘어갈 수 있고, 텍스트만 있는 문제도 가능
· 📚 **오답노트 정렬**：오른쪽 위에 「⇅ 정렬」 추가 —— 최근 추가순 / 오래된 순 / 다음 복습이 빠른 순 / 복습 횟수 많은 순 / **취약한 순**(선택은 기억됩니다)
· 🔎 **복습 필터**：**과목**으로 거르거나 「**취약한 것만**」 표시 가능. 진행률은 필터된 개수로 계산하고, 결과가 없으면 그렇게 알려줍니다
· ⚠️ **저장 시 중복 확인**：같은 문제 지문이 이미 있으면 「**이미 저장했을 수 있습니다**」와 기존 지문을 보여주고, 그래도 저장할 수 있습니다(알림일 뿐 차단하지 않음)
· 🗄 **자동 백업**：설정 → 데이터 관리 → 「자동 백업」. 폴더를 선택하면 **매일 처음 실행할 때** 오답노트를 JSON으로 백업하고 **최근 3개만** 보관(기기 내에만 저장·네트워크 없음·업로드 없음)
· 🌙 **다크 테마 대화 영역 지원**：말풍선·본문·사고 블록 색상이 테마를 따릅니다. 이전에는 다크에서도 말풍선이 밝은 회색이고 사고 블록의 회색 글씨가 거의 보이지 않았습니다
· 🐞 **크래시 로그(기기 내에만)**：크래시 시 버전/기기/전체 스택을 기록(최근 10개). 설정 → 데이터 관리에서 **txt로 내보내기** 또는 삭제 가능
· ⬆️ **업데이트 페이지 개선**：새 버전의 **업데이트 내용을 앱에서 바로 표시**, 「**이 버전 건너뛰기**」(이후 배지 표시 안 함), **마지막 확인 시간** 표시, 실패 시 다시 시도
· 🔍 **내부 품질**：단위 테스트 47개 추가(답안 파싱/버전 비교/백업 정리/정렬/중복 판정/문구 테이블). 푸시할 때마다 자동 실행

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
