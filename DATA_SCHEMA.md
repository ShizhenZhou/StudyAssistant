# 数据文件结构文档（DATA SCHEMA）

> 本文档记录 App **重要的用户数据文件**的数据结构，并按**每个有改动的 App 版本**分别记录其结构。
> **约定：每次修改数据/存储结构，必须同步更新本文件**，注明所属 App 版本与迁移方式，便于日后开发查看和修改。
>
> App 版本号在 `app/build.gradle.kts` 的 `appVersionName`，数据库版本在 `AppDatabase.version`。

---

## 0. 总览

| 数据类型 | 存储位置 | 说明 |
|---|---|---|
| 错题 / 分类 / 会话 | Room 数据库 `study_assistant.db` | SQLite；带 schema 版本号 + migration 自动迁移 |
| 会话消息（追问历史） | `questions.conversationJson`（TEXT） | 序列化后的 `ChatItem[]` |
| 搜题记录（防"忘了存错题本"） | Room 数据库 `solve_sessions` 表 | 与错题本分表，共享永久题目编号；包含在当前 v4 压缩备份中（兼容导入 v3 JSON） |
| 应用主题 | `SharedPreferences("settings")` 的 `theme` | system/light/dark |
| DeepSeek API Key | Android Keystore（AES/GCM） | 运行时经 `KeyManager` 解密；**不存源码** |
| 本机凭据备份 | 仓库之外的本地文件（**不入库**） | 用户手动维护，绝不上传 |

---

## 0.1 压缩备份 v4（v0.6.7，2026-10-26）

主库继续使用现有结构；本次只改变导出包和私有任务暂存，不执行主库迁移，不因应用版本变化提示数据升级。新导出采用 Bak v4，旧 Bak v3 JSON 可导入；v1/v2 仍拒绝。相同永久题号保留本机题目，只有新增题目所需分类和标签会引入，名称映射后的标签关系去重。历史记录沿用永久题号和首次时间，悬空的错题关联在快照中归空，不改主库。

### 私有一致性快照与恢复

- 白名单捕获 categories、tags、questions、question_images、question_tags、review、solve_sessions 的现有业务字段，不包含 Key、设置、草稿、诊断或撤销日志。原图无损，图片顺序、会话角色、消息编号、正文与思考保留。
- API35+ 在专用单线程只读连接内使用 `beginTransactionReadOnly`，WAL 下允许并发写；API28～34 只在 Room 事务内捕获原始字段，完成后释放。压缩、SAF 写入与校验都在事务外。不得单独复制活跃 WAL 数据库文件。
- 字段通过 SQLite BLOB 字节切片，以 64 KiB 缓冲捕获。规范化后为固定七表的逐行文件，长度前缀 UTF-8（-1 表示 NULL，整数为十进制），manifest 为 SAT1 与固定表计数；图片存 `resources/<SHA-256>`，会话 Base64 在内部树中替换为引用，恢复时展开。资源全局去重；路径索引和关联映射保存在私有磁盘 SQLite，不在 Java 中积累整库列表。
- 单个需解析字段/旧 JSON 记录受 `min(16 MiB, maxHeap/16)` 安全预算限制；总题库不再受整库 JSON 字符串预算限制。空间不足、字段过大或格式异常均停止，不改现有数据。
- 恢复先把用户选定的全部文件固定到私有目录，核对格式、卷号、路径、摘要、字段和关联，重建临时数据库。用户预览确认后，在主库一次 Room 事务中从独立只读私有库分段合并（不使用 ATTACH，也不改变 WAL）；重复编号跳过，失败或取消整次回滚，不把部分恢复当成功。

### ZIP 容器与分卷

- 公共文件名为 `SABak-yyyyMMddHHmmss.zip`（手动）或 `SABak_auto-yyyyMMddHHmmss.zip`（自动），时间戳使用本地时间精确到秒。只有分卷才追加 `-1of3` 等后缀，单文件无卷号；格式身份和顺序仍以内置头及完成标记为准。
- `.zip` 使用 Android/JDK 原生 ZIP / DEFLATE，每卷独立 ZIP，默认目标 256 MiB（允许有界 ZIP 头尾开销），不是 PKZIP 跨盘连续流。单文件优先，过大才多卷；恢复多选全卷，内部编号确定顺序，不依赖文件名或选择顺序。
- `header.bin` 使用 DataOutputStream：固定格式字符串、int version=4、UUID 字符串、int 零起始卷号、long snapshotAt。
- `block-%012d.bin`：int magic=0x53414234、long 全局块序号；文件帧为 byte=1、int 路径字节长度、UTF-8 路径、long 总文件长度；数据帧为 byte=2、long 偏移、int 数据长度及字节。块不超过 1 MiB，单段不超过 64 KiB。
- `directory.bin`：int magic=0x53414434、int 条目数，随后重复 long 块序号、int 长度、32 字节 SHA-256；每卷至多4092块。最后一卷 `commit.bin` 包含 magic=0x53414334、总卷数、块数、文件数、原始字节数、内容摘要和目录根摘要。
- 内容摘要按规范路径顺序绑定路径、长度和原始数据，与备份时间、UUID、分卷阈值无关；根摘要绑定每卷头和目录。先限制中央目录大小再打开 ZipFile，并核对 local/central 一致、CRC、SHA 和完成标记；缺卷、混卷、重复卷、重复条目、路径穿越、符号链接和未知版本在写主库前拒绝。
- 显式资源上限：4096卷、路径 UTF-8 4096字节、组件255字节、深度64、单文件1TiB、原始树16TiB；实际还受可用空间和单字段安全预算约束。这些是格式/资源保护上限，不是任意设备均能处理的承诺。

### 外部保存与自动任务

- v0.6.7.1 从实际文件夹独立预检并分别统计手动/自动完整套数，分卷按一套，日期取内部 snapshotAt/exportedAt 并按手机本地日期比较，不依赖 ledger 的存在或文件修改时间。当天已有任何一套完整手动或自动备份则不排自动计划；首次启动 Worker 前再次检查。目录访问失败不显示为零，清理仍只针对自动备份。待执行自动计划可由无延迟的手动 WorkManager 请求替换，代次使旧任务失效；主动立即请求不沿用自动任务的电量/系统低空间等待条件，由流式写入的实际空间检查决定是否能完成；正在执行时拒绝重复接管，任务不绑定页面协程。
- 使用 SAF 选择文件或文件夹；只拥有本次新建且此前不存在的 documentId，不覆盖已有非空文件。每卷关闭后读回 SHA 校验，全部卷成功后才登记完整一套、更新成功时间和解除失败警示。提供器失败、权限丢失、取消不报告成功。
- 自动备份排队前和 Worker 开始前均以非 deleted 错题存在性检查；空错题本不启动任务、不清理旧备份，之后同日新增题目仍可在下次打开时获得资格。手动导出和手动立即备份不受此限制。
- 自动备份由当天打开应用提交约30秒延迟的一次性 WorkManager 唯一任务，要求电量与空间不低；没有周期性唤醒。系统可在关闭页面后继续或重调度，不保证固定时刻，不保证强行停止应用后执行。
- v0.6.7.1 以实际目录中任一完整备份的手机本地日期决定当天跳过；不再因内容指纹相同跳过，其他日期即使内容未变也写出一次。旧的 checked_day/hash 不作为成功或跳过依据。只有实际写入并全部校验成功、且应用仍在前台时，才发零重放的“自动备份成功”悬浮气泡；后台完成、取消、失败和无变化跳过不发成功事件。
- 自动备份最终失败且不在前台时，通过独立 `backup_failures` 渠道发送“自动备份失败”系统通知，固定通知ID覆盖旧提醒，点按按 AppNav 设置树进入备份管理；重试中、手动失败、取消、空库和无变化不发该通知。成功后清除旧失败通知。通知不含题目、图片、文件名或异常原文；Android13+需要通知权限，系统禁用通知时保留页内失败状态并显示授权入口，不把通知发送失败当备份失败的新异常。
- `settings` 中 `backup_job_*` 为私有阶段、代次、任务/文件所有权和白名单错误状态；目录/开关变化、导入、清空使旧任务失效。真实 WorkManager 状态用于修复孤立 active 标记，永久失败等待用户重试或改配置。
- 每日自动备份开始前扫描所选目录，完整自动备份超过3套时先保留最新2套，成功后保留3套并保护本次新套（时钟回拨也保护）。包括旧命名 `study-assistant-auto-*.json` 与旧 v4 自动备份，分卷按一套计数。ledger 已知卷核对原长度和 SHA，未知候选经完整格式与业务预检；删除前再次重新列目录并核对全套摘要。手动、缺卷、损坏及无法识别的文件保留，因此目录总数可能超过3套。
- 私有 `noBackupFilesDir/backup-jobs/<UUID>` 保存 capture/normalize/validation 完成检查点，以完整树摘要绑定内容，最长复用24小时；SAF 仅复用关闭且验证通过的卷。未完成阶段重新执行，不声称支持压缩流任意字节断点恢复。普通后台每轮约7分钟让出，同一阶段连续3次无完成进展则停止并提示手动导出，避免无限重试。手动导出不受该 Worker 时间窗口限制。
- 手动暂存位于 `noBackupFilesDir/backup-manual/<UUID>`，取消/完成/关闭 VM 后清理；下次操作清理超过24小时的孤立自有目录。所有暂存和日志不进入普通备份或 CI。

---

## 1. Room 数据库 `study_assistant.db`

- 当前 **schema version = 10**（`AppDatabase.SCHEMA_VERSION`），自 App v0.6.6.3 起；v0.6.6.5 保持 v10，v0.6.6.2 使用 DB v9。
- 迁移路径：`v1 → v2 → v3 → v4 → v5 → v6 → v7 → v8 → v9 → v10`，全部用 `Migration` + `addMigrations(...)`。
- 保留 v9 → v10 的原地升级；历史 migration 链仍保留。Room 打开旧库时自动迁移，失败整次回滚，不清库重建。v0.6.6.5 无主库迁移，不会仅因应用版本更新再次要求升级。
- 升级前先检查点写入 WAL，再复制为同目录 `study_assistant.db.pre-v<目标版本>-from-v<原版本>-<时间戳>` 并同步落盘；备份失败则停止打开/迁移。此前 `.pre-v9` / `.pre-v10-*` 文件不删除。
- 界面“一键升级”负责检查/补全，不是 Room 主键迁移的前置条件。升级提示仍按 DB 版本管理。
- `settings.data_upgrade_confirmed_schema: Int` 单独记录用户成功执行“一键升级”检查并确认的结构版本。当前结构已触发升级提示且尚未确认时，即使启动自动迁移已完成、无需补图，首次检查成功仍显示“升级成功”；之后无变更时显示“无需升级”。失败不更新确认记录，“修复数据”不消费升级确认。此标记不替代每次完整性检查。
- 数据升级 / 修复页底部展示应用支持的 `DB v{SCHEMA_VERSION} · Bak v{BackupFile.VERSION}`，下一行使用实际打开数据库的 `user_version` 展示当前结构版本。SQLite 每个数据库只有一个结构版本，不为错题伪造逐条 DB 版本。
- 第三行仅在完整性扫描发现仍可补全的旧格式原图数据时显示橙黄色“存在旧版本数据”；不按题目创建时间判断，不把无可恢复原图的旧题判为待升级。页面打开时重新检查，升级 / 修复事务成功后清除警示。

### v0.6.6.6（待发布）：备份健康状态、标签草稿与分析诊断（2026-10-24）

- 主库仍为 DB v10、备份仍为 Bak v3，撤销辅助库和撤销 JSON 仍为 v1；没有主库或备份迁移，也不会因这轮修改触发数据库升级提示。
- settings 新增 `auto_backup_attempt_at` / `auto_backup_failure_key`，只记录最近尝试时间和白名单错误键。旧安装缺字段视为无已知失败，保留原成功时间。真正写入并读回校验成功后清除失败；切换文件夹时重置原文件夹的状态。取消不记成失败，旧文件夹异步结果不得更新新文件夹状态。异常原文和备份内容不写入状态。
- `noBackupFilesDir/tag-merge-draft-v1.json` 是独立草稿，version=1，含标签/关联快照指纹、保存时间及可编辑分组和勾选状态；不保存可执行预览。使用 AtomicFile 原子替换，读写上限 1 MB。不存在视为无草稿；损坏、未知版本或快照过期均不猜测恢复，保留旧文件并在界面说明。下一次用户编辑保存可覆盖，或明确放弃后删除。保存完成前显示正在保存；保存失败提示当前编辑仅在内存，不承诺保住进程退出前尚未写完的编辑。
- 草稿恢复后必须重新预览；最终预览及主库事务中的完整冲突核对保持不变。成功合并/撤销后清除整理草稿，普通编辑按顺序异步保存，旧写入不得越过后续清理重新生效。
- `noBackupFilesDir/tag-analysis-diagnostics-v1.json` 为 version=1 的本机诊断，最多 20 条；只有生成的 UUID、开始时间/耗时、模式、标签数量、固定结果/错误枚举、HTTP 数字、接受/跳过组数。不保存标签名称、题目 ID/正文、图像、思考或响应原文、异常文本、Key、设备信息。未知服务端字符串映射为固定枚举，导出重新编码已验证的 DTO，忽略的原始字段不会透传。文件损坏不覆盖，界面可明确清空后恢复记录。
- 两个本地文件均不进入应用的错题备份 JSON，也不增加联网端点。诊断只在用户点击导出并用 SAF 选择位置后写出；网盘 SAF 后续同步属于所选文件提供器行为。

### v0.6.6.5：标签合并与持久撤销

- 撤销记录独立存于应用私有目录的 **`tag_merge_undo.db`**，由 `SQLiteOpenHelper` 管理，辅助库 `user_version=1`。不是主库表，不影响页面显示的主库 DB v10 / Bak v3。
- 辅助表 `tag_merge_undo`：`id INTEGER NOT NULL PRIMARY KEY`（1 为最近有效批次，2 为待确认批次，正常最多一份有效撤销；操作中短暂保留两份）、`createdAt INTEGER NOT NULL`、`beforeJson TEXT NOT NULL`、`afterJson TEXT NOT NULL`。
- 两个 JSON 使用内部 `version=1`，包含该批次涉及的标签定义（ID、名称、原 createdAt）与题目标签关联；字段完整性和已知版本均需核对，坏记录只拒绝撤销，不猜测修复。无记录视为无可撤销批次。
- 合并前校验完整标签 / 关联预览指纹，核对全部组的来源、目标名、交叉冲突与题目存在性。主库关联迁移、去重和删除来源定义仍在同一个 Room 事务内；提交前先以 `synchronous=FULL` 持久写入辅助库待确认槽，不覆盖旧有效槽；该写入失败则主库回滚。
- 两个库不声称跨库原子提交。主库提交成功后提升候选并覆盖旧槽；辅助提升失败不把已提交的合并报告为失败，而是保留候选待恢复。后续读取或重启时，在主库一致快照上恢复：相关状态等于候选合并后快照时原子提升候选；等于合并前快照时仅丢弃候选，保留上次撤销；均不匹配或记录损坏时清除辅助记录，不猜测、不改题目。合并 / 撤销 / 恢复 / 导入失效操作由同一个进程内互斥锁串行执行。
- 撤销仅恢复该批次涉及的标签与关联；当前相关状态必须与合并后快照一致，且原名称未在组外被重新创建。不相关后续编辑保留。撤销主库事务提交前不清日志，失败可重试；提交后清理日志，如清理失败则下次恢复发现原状态已还原时再清除，避免重复执行。新合并成功后覆盖旧日志，仅支持最近一次，不是多步撤销栈。
- 备份仍为 v3，导出正常业务数据，不导出本机撤销库。开始导入 / 清空的写入操作前先清除撤销记录，避免中断后误撤销；保守地，随后导入事务失败也不恢复撤销记录（业务数据仍按事务回滚）。读取 / 解码即失败、尚未进入导入的备份不触发此清除。辅助库丢失只失去撤销，不修改主库数据；不可读时停止撤销操作。
- AI 标签整理是新增联网场景：用户主动分析时发送完整标签 ID、名称、关联题数；不发送题目 ID、正文、答案、图片或对话。与解题会话独立，AI 输出只作为可编辑候选，无法直接写库。
- 2026-10-24 同版本附件的候选读取修正仅增加网络 DTO 的可选 `response_format` / `finish_reason`，不进入数据库、备份或撤销日志。主库 v10、备份 v3、辅助库 v1 及日志 JSON v1 均不变，无数据迁移。交叉 / 目标冲突候选可供编辑，但最终预览与落库校验不变。
- `QuestionDao` 的单条 / 批量标签关联写入在事务内核对标签是否存在；过期选择会报错并回滚，不能写入已被合并删除的标签 ID。单题标签重置也改为事务，避免先清空后写入失败。

### v0.6.6.4：备份与搜题记录写入保障（格式不变）

- DB 仍为 v10、备份仍为 v3，无新增迁移。自动备份在关闭输出流后重新读取完整文件，SHA-256 一致才清理旧备份与记录成功时间；串行执行自动 / 手动备份和备份清理，并固定本次操作的 SAF 文件夹。写入或校验失败时仅尝试删除本次创建的文件，不清理旧备份；文件提供器拒绝删除时可能残留失败文件。
- 导入在后台两次打开输入流：先读取格式头，再直接解码 DTO 并校验，避免整文件文本与通用 JSON 树常驻。仍保留整份备份的编码内容，不是恒定内存的流式导入；图片按题目 / 会话逐条解码，已存在的编号跳过解码与覆盖。全部校验通过后仍在一个 Room 事务内合并，失败整体回滚。
- 搜题记录失败快照按题目编号保存在本次运行的内存中，每题只保留最新待写内容；界面显示数量与重试入口。首次提交失败可按原编号重试新增，其余缺失记录不补建；超过历史保留天数的失败新增也不补建。删除 / 裁剪时注销对应待重试票据，清空全部数据时清空队列。队列不随备份导出、不保证进程被杀后保留，因此提示用户关闭应用前重试。

### 1.0 表 `question_images`（**v9 新增**：一题多图）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | INTEGER (PK, auto) | — | 主键 |
| `questionId` | TEXT | — | 指向 `questions.id`，永久编号 |
| `position` | INTEGER | — | **0 = 主图**（卡片/复习优先用它），1.. = 其余原图 |
| `bytes` | BLOB | — | 原始 JPEG（**不存 base64**） |
| `source` | TEXT | NULL | `save` / `upgrade` / `repair` / `import` / `migrate`（诊断用） |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | 写入时间 |

> 索引 `(questionId, position)`。
> **兼容策略**：`questions.imageBytes` 列**保留不删**；读取一律「`question_images` → `imageBytes` → `conversationJson`」三层兜底。
> 保存错题时把全部图写进本表；`v8→v9` 迁移用纯 SQL 把旧 `imageBytes` 搬成 `position=0`（旧数据原地升级）。

### 1.1 表 `questions`（最新结构 = v10）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | TEXT (PK) | 无，创建入口必须提供 | UUID v4＋首次输入时间戳，格式 `<uuid>-<毫秒>` |
| `text` | TEXT | — | 题目文字 |
| `answer` | TEXT | — | 解答内容 |
| `createdAt` | INTEGER | 首次输入时间 | 与永久编号后缀一致；追问、重新生成、保存、导入均不刷新 |
| `imageBytes` | BLOB | NULL | 框选出的错题图片（JPEG 字节；纯文字题为 NULL） |
| `conversationJson` | TEXT | NULL | 完整对话会话（`ChatItem[]` 的 JSON），用于续答 |
| `deleted` | INTEGER | `0` | 是否软删除（1=软删除，可从错题本恢复） |
| `categoryId` | INTEGER | NULL | 所属分类 `categories.id`；NULL = 暂不分类 |

> 相关联 DAO 方法：`getAll`（`WHERE deleted=0 ORDER BY createdAt DESC`）、`insert`、`update`、`softDelete`、`restore`、`deleteById`、`setCategoryForIds`（批量改分类）、`deleteByIds`（批量彻底删除）。

### 1.2 表 `categories`（v5 新增）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | INTEGER (PK, auto) | — | 主键 |
| `name` | TEXT | — | 分类名（如 高等数学、微积分） |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | 创建时间 |

> `questions.categoryId` 为外键语义引用 `categories.id`；`GET`DB 删除分类时用 `DELETE FROM categories WHERE id=:id`。

### 1.2.1 表 `tags`（v6 新增）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | INTEGER (PK, auto) | — | 主键 |
| `name` | TEXT | — | 知识点标签名（如 分部积分、二重积分） |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | 创建时间 |

### 1.2.2 表 `question_tags`（v6 新增，多对多关联）

| 列名 | 类型 | 说明 |
|---|---|---|
| `questionId` | TEXT | 语义引用 → `questions.id`，永久编号 |
| `tagId` | INTEGER | 外键 → `tags.id` |

> 复合主键 `(questionId, tagId)`；一道题最多 5 个 tag（业务层限制）。

### 1.2.3 表 `review`（v7 新增，艾宾浩斯复习调度）

| 列名 | 类型 | 含义 |
|---|---|---|
| `questionId` | TEXT (PK) | 语义引用 → `questions.id`，一题一条 |
| `intervalStep` | INTEGER | 当前间隔档位（0..6，对应 `[0,1,2,4,7,15,30]` 天） |
| `nextReviewAt` | INTEGER | 下次复习时间戳 |
| `lastReviewedAt` | INTEGER | 上次复习时间戳 |
| `reviewCount` | INTEGER | 复习次数 |

> 三按钮：熟悉=+1 档；模糊=保持档位；忘记=重置第 0 档（今天）。

### 1.2.4 表 `solve_sessions`（v8 新增，v10 使用永久编号）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | TEXT (PK) | 无，首次提交时提供 | 与同一道错题的 `questions.id` 一致 |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | 本次会话创建时间（更新时**不刷新**，用于排序与裁剪） |
| `updatedAt` | INTEGER | `System.currentTimeMillis()` | 最后一次写入时间 |
| `source` | TEXT | `"camera"` | 来源：`camera`（拍照）/ `ask`（图文提问）/ `text`（文字）/ `grade`（批改） |
| `text` | TEXT | `""` | 题干（空则回退 `"（图片题）"`，与 `saveToNotebook` 同规则） |
| `answer` | TEXT | `""` | 最后一次 AI 回答 |
| `conversationJson` | TEXT | NULL | 完整对话会话（与 `questions.conversationJson` **同一格式**：`ChatItem[]` 的 JSON） |
| `imageBytes` | BLOB | NULL | 单图（拍照）原图；纯文字题为 NULL |
| `imagesJson` | TEXT | NULL | 多图（**base64 数组**的 JSON）；顺带修"多图拍题存错题本丢原图" |
| `savedQuestionId` | TEXT | NULL | 已存错题本时与本行 `id` 相同；NULL = 无对应错题 |

> 与错题本分表存储，共享题目永久编号；备份 v3 包含两表。首次拍题/提问才建历史，从错题本/复习打开不新增；已删/裁剪的历史不自动复活。
> 相关联 DAO 方法（都在现有 `QuestionDao` 里）：
> - `solveSessionsLight(): Flow<List<SolveSession>>` —— **列表用轻查询**（只选 `id/createdAt/updatedAt/source/text/answer/savedQuestionId`，
>   **不含 BLOB 与 `conversationJson`**）→ 否则会重演 CursorWindow 2MB 崩溃；
> - `solveSessionOnce(id)` —— 进详情时按 id 取**重字段**；
> - `insertSolveSession`、`updateSolveSession`（按 id 更新同一条）、`countSolveSessions`、
>   `solveSessionsLightOnce`（裁剪用）、`deleteSolveSessionsByIds`、`deleteSolveSessionsBefore`。
>
> **保留策略**：最近 **200 条 / 30 天，两者先到先删**（`SOLVE_SESSION_KEEP` / `SOLVE_SESSION_KEEP_DAYS`），
> 启动时挂在 `purgeDeletedData()` 旁边执行；判据是纯函数 `solveSessionsToDelete(...)`（`data/SolveSessionPrune.kt`，有单测）。
> **记录时机**：4 个生成入口的 `onDone` 里**尽早 insert**（进程被杀也不丢），追问/继续生成 `UPDATE` 同一条。

### 1.3 迁移历史（每个版本对应的结构）

| 迁移 | App 版本 | 结构变化 | SQL |
|---|---|---|---|
| —（v1 初始） | v0.1 | 建表 `questions(id,text,answer,createdAt)` | 初始建表 |
| `MIGRATION_1_2` | v0.2 | questions 加 `imageBytes`(BLOB) | `ALTER TABLE questions ADD COLUMN imageBytes BLOB` |
| `MIGRATION_2_3` | v0.3.3 | questions 加 `conversationJson`(TEXT) | `ALTER TABLE questions ADD COLUMN conversationJson TEXT` |
| `MIGRATION_3_4` | v0.3.4 | questions 加 `deleted`(INT NOT NULL DEFAULT 0) | `ALTER TABLE questions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0` |
| `MIGRATION_4_5` | v0.3.6 | questions 加 `categoryId`(INT)；新建表 `categories` | `ALTER TABLE questions ADD COLUMN categoryId INTEGER`；`CREATE TABLE categories(...)` |
| `MIGRATION_5_6` | v0.3.9 | 新建表 `tags` 与 `question_tags` | `CREATE TABLE tags(...)`；`CREATE TABLE question_tags(...)` |
| `MIGRATION_6_7` | v0.4.0 | 新建表 `review`（艾宾浩斯复习调度） | `CREATE TABLE review(questionId PK, intervalStep, nextReviewAt, lastReviewedAt, reviewCount)` |
| `MIGRATION_7_8` | v0.6.5 | 新建表 `solve_sessions`（搜题记录；与 `questions` 隔离，现有查询一律不动） | `CREATE TABLE solve_sessions(...)` |
| `MIGRATION_8_9` | v0.6.6 | 新建表 `question_images`（一题多图，二进制、有序）+ 索引；**旧 `imageBytes` 搬成 `position=0`** | `CREATE TABLE question_images(...)`；`CREATE INDEX index_question_images_questionId_position ...`；`INSERT INTO question_images(questionId,position,bytes,source,createdAt) SELECT id,0,imageBytes,'migrate',createdAt FROM questions WHERE imageBytes IS NOT NULL` |
| `MIGRATION_9_10` | v0.6.6.3 | 题目及所有题目引用改为 TEXT 永久编号；补齐旧时间 | 迁移前备份；映射复制五表；数量校验；事务回滚保护 |

> 代码位置：`app/src/main/java/com/zsz/studyassistant/data/Question.kt`（`Question`、`Category`、`QuestionDao`、`AppDatabase`、各 `MIGRATION_*`）
> 与 `data/SolveSession.kt`（`SolveSession` 实体）、`data/SolveSessionPrune.kt`（裁剪纯函数）。
> **`SolveSession` 实体单独一个文件**是刻意的：本机单测是纯 JVM 且手工拼 classpath（不含 Room），
> 与 `@Dao`/`Migration` 同文件会让纯函数测试加载不到 `androidx.room.migration.Migration` 而报 `NoClassDefFoundError`。

---

## 1.4 数据安全修复约定（v0.6.6.2，2026-10-22）

- Room 仍为 **v9**，备份仍为 **v2**，不增加表 / 列，不改变 JSON 格式；本次无数据库迁移。
- `question_images` 补全在事务内按字节内容去重，仅追加缺失原图；保留已有主图、顺序和记录，不先删后写。
- 原图最多 3 张；已存在的超限或重复记录不在本次修复中删除。候选超出剩余名额时不覆盖已有原图。
- 搜题记录明确关联的错题尚存在且未软删除时，打开记录读取错题最新内容、分类及标签，退出仍更新该条记录；
  对应错题不存在或已软删除时按独立记录查看，不自动恢复或覆盖它。
- 图片修复只使用 `savedQuestionId` 明确关联的搜题记录，不凭相同题干猜配；无法证明归属的图片保留原处。
- 手动升级 / 修复先检查当前可恢复数据的完整性；无需变更时，升级入口提示“无需升级”，修复入口提示“数据已完整，无需修复”。有可恢复缺图才写入，整次操作失败回滚。没有任何原图来源的旧题无法据此恢复图片，手动修复的进一步改进暂缓。
- 新增 `SharedPreferences("settings")` 键（旧安装缺失时使用默认值，原偏好和 DB 均保留）：
  - `image_repair_schema: Int`、`image_repair_last_success_at: Long`：成功检查 / 修复后的数据库版本与时间。
  - `data_upgrade_seen_schema: Int`、`data_upgrade_prompted_schema: Int`：见过 / 提示过的 DB 版本。
  - 不再按 App 小版本重复提示数据升级；原 `upgrade_prompt_version` 是 `0.6.6` / `0.6.6.1` 时兼容为已提示 DB v9。
  - 新安装且数据完整时不提示；已有安装的 DB 升版，或该 DB 首次检出旧格式时提示。
    同 DB 选择跳过后不重复启动弹窗，实际读到旧数据时仍保留警示。成功修复记录不代替之后的完整性检查。
- 新键只是诊断 / 提示状态，不用于跳过 Room migration、不替代迁移前 DB 备份；备份 v2 不包含这些偏好，
  导入旧数据后会重新检查。未来 DB 升版仍需新增 migration 与对应迁移前备份策略。

---

## 1.5 永久编号与备份 v3（2026-10-23，v0.6.6.3）

- 新题编号：`<随机 UUID v4>-<首次输入毫秒时间>`，与设备标识无关。题目本地主键及所有题目引用均为 TEXT；分类、标签、图片行自身编号继续用 INTEGER。
- 旧数字编号仅在迁移映射表中使用。按编号顺序保留有效时间；连续缺时间的题在前后有效时间之间随机补值并排序；前后倒置仍在两时间之间取值；不足 1 毫秒或两边相同则沿用较小值。
- 首部逐题向前 1 秒、尾部逐题向后 1 秒；极端越过时间下界时限制为 1 毫秒。全未知使用执行迁移时的时间。UUID 保证唯一，时间戳允许相同。
- 迁移以 SQL 复制保留题目、二进制图片、分类引用、标签和复习进度；孤儿关联也映射保留，不静默丢弃。替换旧表前比较五张表记录数，不一致即回滚。
- 用户已清空旧历史，不要求兼容旧历史关联；若残留历史，独立生成编号并保留内容，不自动关联或覆盖错题本。
- 新历史只在首次输入时创建；追问、重试、重新生成、存题、退出只更新已有行。错题本/复习加载关闭历史写入。分组、列表显示及排序只用 `createdAt`，`updatedAt` 仅用于诊断。
- `MIGRATION_9_10` 调用 `IdentityMigrationPlan`，测试使用同一份实际 SQL 计划，与 Room 生成的 v10 表结构对比，并验证失败回滚。

备份 v3：

- 顶层仍为 JSON：`format="study-assistant-backup"`、`version=3`、`exportedAt`、`categories`、`tags`、`questions`、`questionTags`、`reviews`、`solveSessions`、`stats`。
- `questions[].id`、`questionTags[].questionId`、`reviews[].questionId`、`solveSessions[].id/savedQuestionId` 改为永久编号字符串。分类/标签编号保留数字，导入按名称重映射。
- `questions[].images`、`solveSessions[].images` 为原图 base64 数组，`imageBase64` 必须与首图一致；对话仍为 `ChatItem[]`，消息 `id` 是序号，不是题目编号或时间。
- 仅接受 v3，v1/v2 或更新格式均在写库前拒绝（用户已授权不兼容老备份）。旧 v2 备份保持原文件不变；迁移后必须重新导出 v3。
- 导出在后台线程使用一致的数据库事务快照。导入预先校验格式、编号/时间、重复键、图片和关联，在一个事务内合并；坏文件不部分导入。
- 按永久编号判重：同编号保留本机题目/对话/分类/标签/复习进度，不覆盖；缺失题目及历史才恢复。题干相同、编号不同仍是不同题。不向已有错题重复追加标签或复习记录。
- 新导入历史若本机存在同编号错题，关联至该题；先有历史、后恢复错题时也按编号补关联，不改历史时间或内容。重复导入不新增题目/历史。
- 当前导入策略不自动合并同编号的内容差异，也不恢复现有同编号题目中缺失的图片；此类冲突保留本机内容，可另行设计用户确认流程。

---

## 2. 会话消息结构（存于 `questions.conversationJson`）

每条错题保存的是 `ChatItem[]`（JSON 数组），序列化用 `kotlinx.serialization`（`Json { ignoreUnknownKeys=true }`）。

```kotlin
@Serializable
data class ChatItem(
    val id: Long,
    val role: String,        // "user" | "assistant" | "question"
    val content: String,
    val images: List<String>? = null,  // base64 编码的追问附图（1~3 张）
    val thinking: String? = null       // v0.6.0 新增：该轮 AI 的思考过程（思考流）；旧 JSON 无此键 → null
)
```

> **v0.6.0 变更（思考流）**：`ChatItem` 新增可空字段 `thinking`（默认 `null`）。
> **不涉及 Room 表结构**（仍存在 `questions.conversationJson` 这个 TEXT 列里），
> 因此**数据库版本保持 v7、无需 Migration**；旧会话 JSON 缺少该键 → 反序列化为 `null`（`ignoreUnknownKeys=true`），
> 旧数据读取与显示不受影响；新备份在旧版本上读取时该键会被忽略。

- 代码位置：`app/src/main/java/com/zsz/studyassistant/MainViewModel.kt`（`ChatItem`）。
- 构建给模型的对话历史见 `MainViewModel.buildMessages()`。

---

## 3. DeepSeek API 消息结构

`DeepSeekMessage(role: String, content: JsonElement)`：

- `content` 为**字符串** → 纯文本消息。
- `content` 为**数组**（`[{type:"text",text:..},{type:"image_url",image_url:{url:"data:image/jpeg;base64,.."}}]`）→ 视觉消息（文字 + 多图）。

视图相关：`gradeWithImages`、`visionUserMessage`、`userMessageWithImages` 在此文件：`app/src/main/java/com/zsz/studyassistant/data/StudyAssistant.kt`。

---

## 4. 本机凭据文件（仓库之外，不入库，用户自管）

- 位置：**Git 仓库之外的本地目录**，由用户手动维护。
- **绝不上传**；项目 `.gitignore` 也忽略了 `local_secrets*`/`*.key`/`*.pem`/`*.p12` 等模式。
- 用途示例（仅列出键名，值不入库）：

```ini
DEEPSEEK_API_KEY=
BAIDU_ACCESS_KEY=
BAIDU_SECRET_KEY=
```

---

## 5. 修改数据结构时的约定

1. 主库表结构改变时，修改实体、递增 `AppDatabase.version` 并新增迁移；纯 DAO 查询或独立本机文件变化不因此递增主库版本。
2. **必须在本文件**补一行「迁移历史」，注明 App 版本与 SQL，并把「最新结构」表更新。
3. 自定义文件必须显式记录格式版本并校验；现行 JSON 备份、草稿、诊断和恢复记录使用 JSON 的 `version` 字段，不追加二进制版本头。新增格式应明确兼容、损坏处理和备份范围。
4. 改完后 `assembleDebug` 验证编译，再提交推送。


## 6. 待保存内容恢复记录（v0.6.6.7）

- 主库仍为 DB v10，普通备份仍为 Bak v3；本次仅新增 DAO 内容更新操作，无主库迁移。
- 路径：应用私有 `noBackupFilesDir/pending-saves-v1/{history|notebook}-<永久题号>.json`，单文件上限 32 MiB，使用 AtomicFile 写入并读回核对。旧安装无目录视为无待保存内容。
- JSON `version=1`；字段 `target`、`payload`、`expected`、`token`。payload 含题号、首次时间、题干、答案、对话；搜题记录另含更新时间、来源、主图 base64、多图 JSON。
- expected 为提交前内容的 SHA-256、首次新建时的 null，或无法读取基线时的 `unavailable`。恢复时先确认当前内容已经等于目标，或仍匹配基线，再提交；未知基线、内容变化和已删除错题保留副本，不覆盖、不复活。分类、标签、软删除和复习状态不属于错题内容回放字段。
- 提交前写恢复记录，数据库事务成功后按 token 清除；旧确认不能清掉较新的副本。启动与手动重试执行恢复；超过历史保留期限的记录不重建。历史删除/裁剪先使对应恢复记录失效；导入或清空要求先处理待保存内容。
- 未知版本/损坏记录保留并提示；恢复文件写失败时内存内容仍可重试或导出，但不能承诺进程结束后保留。只有完整提交后显示已保存；放弃副本不显示保存成功。
- 恢复文件不进入普通 Bak v3、不参与云恢复；用户明确选择导出时写入 SAF 位置，导出格式 `study-assistant-pending-saves` / version 1，仅供内容取回，不作为普通备份导入。包含可读记录及仍在内存的副本，损坏记录不假称已导出；明确确认放弃才清除此恢复目录，不改已有数据库内容。
- 包内 `assets/build-time-utc.txt` 为 14 位 UTC `yyyyMMddHHmmss` 构建标识，无用户数据；新 APK 的附件名使用同一时间。同版本比较不使用手机安装时间；旧包没有该文件时通过摘要兜底。

## 对话草稿与读取保护（v0.6.6.10）

- 主库结构与普通备份格式均未改变，无需结构迁移。列表不再读取图片及会话 JSON；详情的 conversationJson、历史 imagesJson 通过 SQLite 字符分段读取，避免单行会话超过 CursorWindow。图片在可见卡片需要时后台读取、降采样。
- `noBackupFilesDir/conversation-drafts-v1/<永久题目编号>.json` 保存独立对话草稿（version 1）：原会话、请求、已收片段、思考、生成编号、递增 revision、操作类型、来源与已存内容摘要；完成草稿附完整结果。原子写入并读回核对，旧请求或旧 revision 不覆盖新草稿，明确放弃及删除题目使旧写入失效。不含 Key，排除系统备份、普通 JSON 备份和 CI。
- 每轮请求前建立草稿，生成时约每秒保存一次已刷新片段；退出或中止时补快照，完成后先提交错题和既有记录，再清理草稿。突然断电可能丢失最近尚未落盘的片段；写盘失败明确提示，不承诺恢复。恢复校验题目内容摘要，冲突保留草稿，不覆盖后续修改。
- 救援导出的 `study-assistant-pending-saves` version 1 增加可选 `interruptedDrafts` 字段，供人工协助恢复，不是普通备份的另一种导入格式。原 pending entries 字段保持兼容；损坏文件仍保留，不声称已经成功导出其内容。
- 删除采用内存内 3 秒待确认批次：仅从列表隐藏，撤销不写库；到期事务删除题目及关联/历史/分类，失败回滚并重显。快速连续删除合并批次并重新计时；窗口中进程结束视为未提交删除，不提供长期回收站。
- 导入同编号错题保留本机内容；仅导入实际新增错题所需的分类和标签，不因已跳过的题目恢复旧标签。
- 当前备份仍以完整对象编码：导入字节数上限为 `min(48 MiB, 最大堆 / 8)`；导出先统计原始字段体量，超过该上限的三分之一时提前拒绝。该保守限制用于避免内存耗尽，不代表任意规模备份均受支持；失败不修改数据库、不清理旧备份。大规模流式备份作为后续独立改进。
- 复习用自然日安排下一次日期，未保存不前进；本轮队列来自入口实际筛选。每日/每周复习数和连续天数仍由每题最近复习时间估算，新增题目趋势按首次时间统计，未新增完整复习事件表。


## 读取与草稿可靠性维护（v0.6.6.12，2026-10-25）

- 不改变主库结构、备份字段或草稿 version，无需迁移。列表题干/答案只取前 256 个 SQLite 字符；详情、查重和备份使用完整字段，禁止把预览写回数据库。
- 详情的题干、答案、会话与历史附图 JSON 按字符分段读取；三处图片来源（questions、solve_sessions、question_images）按 64 KiB 字节块读取。NULL 与空图片保持区分。单个二进制图片超过 `min(32 MiB, 最大堆 / 4)` 时停止读取，不分配无限内存；原库保持不变。
- 错题本搜索独立在 IO 线程逐题读取完整题干/答案，保留 Unicode 忽略大小写匹配，不限于卡片预览；输入变更取消旧搜索，失败与空结果分开显示。
- 单次生成结束或放弃的草稿清理核对永久题号及 generation，旧清理不能删掉新生成的草稿。题目真正删除仍按永久题号注销全部对应写入。
- 完整草稿只有在读回匹配的持久错题或搜题记录后才清理；没有目标或保存失败时继续保留草稿。用户主动“清空所有数据”时，先停止生成并等待收尾，在保存队列保护下清除学习数据和对话草稿，注销已排队的旧草稿写入，不删除外部备份或修改应用设置。
- 单轮复习内区分已提交与切题失败；切题重试不再登记同题第二次复习或再次增加进度。开始新一轮时重置内存标记，不引入永久复习日志表。
