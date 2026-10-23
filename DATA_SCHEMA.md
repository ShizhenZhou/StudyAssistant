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
| 搜题记录（防"忘了存错题本"） | Room 数据库 `solve_sessions` 表 | 与错题本分表，共享永久题目编号；包含在备份 v3 中 |
| 应用主题 | `SharedPreferences("settings")` 的 `theme` | system/light/dark |
| DeepSeek API Key | Android Keystore（AES/GCM） | 运行时经 `KeyManager` 解密；**不存源码** |
| 本机凭据备份 | 仓库之外的本地文件（**不入库**） | 用户手动维护，绝不上传 |

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

### v0.6.6.5：标签合并与持久撤销

- 撤销记录独立存于应用私有目录的 **`tag_merge_undo.db`**，由 `SQLiteOpenHelper` 管理，辅助库 `user_version=1`。不是主库表，不影响页面显示的主库 DB v10 / Bak v3。
- 辅助表 `tag_merge_undo`：`id INTEGER NOT NULL PRIMARY KEY`（1 为最近有效批次，2 为待确认批次，正常最多一份有效撤销；操作中短暂保留两份）、`createdAt INTEGER NOT NULL`、`beforeJson TEXT NOT NULL`、`afterJson TEXT NOT NULL`。
- 两个 JSON 使用内部 `version=1`，包含该批次涉及的标签定义（ID、名称、原 createdAt）与题目标签关联；字段完整性和已知版本均需核对，坏记录只拒绝撤销，不猜测修复。无记录视为无可撤销批次。
- 合并前校验完整标签 / 关联预览指纹，核对全部组的来源、目标名、交叉冲突与题目存在性。主库关联迁移、去重和删除来源定义仍在同一个 Room 事务内；提交前先以 `synchronous=FULL` 持久写入辅助库待确认槽，不覆盖旧有效槽；该写入失败则主库回滚。
- 两个库不声称跨库原子提交。主库提交成功后提升候选并覆盖旧槽；辅助提升失败不把已提交的合并报告为失败，而是保留候选待恢复。后续读取或重启时，在主库一致快照上恢复：相关状态等于候选合并后快照时原子提升候选；等于合并前快照时仅丢弃候选，保留上次撤销；均不匹配或记录损坏时清除辅助记录，不猜测、不改题目。合并 / 撤销 / 恢复 / 导入失效操作由同一个进程内互斥锁串行执行。
- 撤销仅恢复该批次涉及的标签与关联；当前相关状态必须与合并后快照一致，且原名称未在组外被重新创建。不相关后续编辑保留。撤销主库事务提交前不清日志，失败可重试；提交后清理日志，如清理失败则下次恢复发现原状态已还原时再清除，避免重复执行。新合并成功后覆盖旧日志，仅支持最近一次，不是多步撤销栈。
- 备份仍为 v3，导出正常业务数据，不导出本机撤销库。开始导入 / 清空的写入操作前先清除撤销记录，避免中断后误撤销；保守地，随后导入事务失败也不恢复撤销记录（业务数据仍按事务回滚）。读取 / 解码即失败、尚未进入导入的备份不触发此清除。辅助库丢失只失去撤销，不修改主库数据；不可读时停止撤销操作。
- AI 标签整理是新增联网场景：用户主动分析时发送完整标签 ID、名称、关联题数；不发送题目 ID、正文、答案、图片或对话。与解题会话独立，AI 输出只作为可编辑候选，无法直接写库。
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

1. **先改实体**（`Question`/`Category`/`ChatItem` 等），同步改 `AppDatabase.version` 并**新增一个迁移**。
2. **必须在本文件**补一行「迁移历史」，注明 App 版本与 SQL，并把「最新结构」表更新。
3. 若用到**自定义文件**（如导出/备份），按约定在文件**最前 4 字节写版本号**（小端 `Int`），读取时先读版本再按版本迁移；同时在本文档记录该文件格式。
4. 改完后 `assembleDebug` 验证编译，再提交推送。
