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
| 搜题记录（防"忘了存错题本"） | Room 数据库 `solve_sessions` 表 | v8 新增；**与错题本完全隔离**，不进 `questions`、**本期也不并入 JSON 备份** |
| 应用主题 | `SharedPreferences("settings")` 的 `theme` | system/light/dark |
| DeepSeek API Key | Android Keystore（AES/GCM） | 运行时经 `KeyManager` 解密；**不存源码** |
| 本机凭据备份 | 仓库之外的本地文件（**不入库**） | 用户手动维护，绝不上传 |

---

## 1. Room 数据库 `study_assistant.db`

- 当前 **schema version = 9**（`AppDatabase.version`）。
- 迁移路径：`v1 → v2 → v3 → v4 → v5 → v6 → v7 → v8 → v9`，全部用 `Migration` + `addMigrations(...)`。
- **维护约定（2026-10-16 起）**：只保证 **v9 及更新格式**；v8 及更老的数据**装上新版会自动迁移**，
  迁移前会先把 DB 文件复制成 `study_assistant.db.pre-v9`（同目录，异常可拷回）。
  App 启动时若检测到"有图但还没进 `question_images`"的旧数据，会弹一次升级提示（见「数据升级 / 修复」页）。

### 1.0 表 `question_images`（**v9 新增**：一题多图）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | INTEGER (PK, auto) | — | 主键 |
| `questionId` | INTEGER | — | 指向 `questions.id` |
| `position` | INTEGER | — | **0 = 主图**（卡片/复习优先用它），1.. = 其余原图 |
| `bytes` | BLOB | — | 原始 JPEG（**不存 base64**） |
| `source` | TEXT | NULL | `save` / `upgrade` / `repair` / `import` / `migrate`（诊断用） |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | 写入时间 |

> 索引 `(questionId, position)`。
> **兼容策略**：`questions.imageBytes` 列**保留不删**；读取一律「`question_images` → `imageBytes` → `conversationJson`」三层兜底。
> 保存错题时把全部图写进本表；`v8→v9` 迁移用纯 SQL 把旧 `imageBytes` 搬成 `position=0`（旧数据原地升级）。

### 1.1 表 `questions`（最新结构 = v5）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | INTEGER (PK, auto) | — | 主键 |
| `text` | TEXT | — | 题目文字 |
| `answer` | TEXT | — | 解答内容 |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | **提问/保存时时间戳**（点进去退出不再刷新） |
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
| `questionId` | INTEGER | 外键 → `questions.id` |
| `tagId` | INTEGER | 外键 → `tags.id` |

> 复合主键 `(questionId, tagId)`；一道题最多 5 个 tag（业务层限制）。

### 1.2.3 表 `review`（v7 新增，艾宾浩斯复习调度）

| 列名 | 类型 | 含义 |
|---|---|---|
| `questionId` | INTEGER (PK) | 外键 → `questions.id`，一题一条 |
| `intervalStep` | INTEGER | 当前间隔档位（0..6，对应 `[0,1,2,4,7,15,30]` 天） |
| `nextReviewAt` | INTEGER | 下次复习时间戳 |
| `lastReviewedAt` | INTEGER | 上次复习时间戳 |
| `reviewCount` | INTEGER | 复习次数 |

> 三按钮：熟悉=+1 档；模糊=保持档位；忘记=重置第 0 档（今天）。

### 1.2.4 表 `solve_sessions`（v8 新增，搜题记录）

| 列名 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `id` | INTEGER (PK, auto) | — | 主键 |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | 本次会话创建时间（更新时**不刷新**，用于排序与裁剪） |
| `updatedAt` | INTEGER | `System.currentTimeMillis()` | 最后一次写入时间 |
| `source` | TEXT | `"camera"` | 来源：`camera`（拍照）/ `ask`（图文提问）/ `text`（文字）/ `grade`（批改） |
| `text` | TEXT | `""` | 题干（空则回退 `"（图片题）"`，与 `saveToNotebook` 同规则） |
| `answer` | TEXT | `""` | 最后一次 AI 回答 |
| `conversationJson` | TEXT | NULL | 完整对话会话（与 `questions.conversationJson` **同一格式**：`ChatItem[]` 的 JSON） |
| `imageBytes` | BLOB | NULL | 单图（拍照）原图；纯文字题为 NULL |
| `imagesJson` | TEXT | NULL | 多图（**base64 数组**的 JSON）；顺带修"多图拍题存错题本丢原图" |
| `savedQuestionId` | INTEGER | NULL | 已「存错题本」时指向 `questions.id`（NULL = 还是"记录"，可一键存） |

> **与错题本完全隔离**：错题本 / 复习 / 统计 / 查重 / 备份的查询**一律不碰这张表**（新表的意义就在这里）。
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
