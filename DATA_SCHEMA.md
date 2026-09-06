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
| 应用主题 | `SharedPreferences("settings")` 的 `theme` | system/light/dark |
| DeepSeek API Key | Android Keystore（AES/GCM） | 运行时经 `KeyManager` 解密；**不存源码** |
| 本机凭据备份 | 工作区根 `LOCAL_SECRETS.txt`（**在仓库外**） | 用户手动维护，绝不上传 |

---

## 1. Room 数据库 `study_assistant.db`

- 当前 **schema version = 5**（`AppDatabase.version`）。
- 迁移路径：`v1 → v2 → v3 → v4 → v5`，全部用 `Migration` + `addMigrations(...)`。

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

### 1.3 迁移历史（每个版本对应的结构）

| 迁移 | App 版本 | 结构变化 | SQL |
|---|---|---|---|
| —（v1 初始） | v0.1 | 建表 `questions(id,text,answer,createdAt)` | 初始建表 |
| `MIGRATION_1_2` | v0.2 | questions 加 `imageBytes`(BLOB) | `ALTER TABLE questions ADD COLUMN imageBytes BLOB` |
| `MIGRATION_2_3` | v0.3.3 | questions 加 `conversationJson`(TEXT) | `ALTER TABLE questions ADD COLUMN conversationJson TEXT` |
| `MIGRATION_3_4` | v0.3.4 | questions 加 `deleted`(INT NOT NULL DEFAULT 0) | `ALTER TABLE questions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0` |
| `MIGRATION_4_5` | v0.3.6 | questions 加 `categoryId`(INT)；新建表 `categories` | `ALTER TABLE questions ADD COLUMN categoryId INTEGER`；`CREATE TABLE categories(...)` |

> 代码位置：`app/src/main/java/com/zsz/studyassistant/data/Question.kt`（`Question`、`Category`、`QuestionDao`、`AppDatabase`、各 `MIGRATION_*`）。

---

## 2. 会话消息结构（存于 `questions.conversationJson`）

每条错题保存的是 `ChatItem[]`（JSON 数组），序列化用 `kotlinx.serialization`（`Json { ignoreUnknownKeys=true }`）。

```kotlin
@Serializable
data class ChatItem(
    val id: Long,
    val role: String,        // "user" | "assistant" | "question"
    val content: String,
    val images: List<String>? = null   // base64 编码的追问附图（1~3 张）
)
```

- 代码位置：`app/src/main/java/com/zsz/studyassistant/MainViewModel.kt`（`ChatItem`）。
- 构建给模型的对话历史见 `MainViewModel.buildMessages()`。

---

## 3. DeepSeek API 消息结构

`DeepSeekMessage(role: String, content: JsonElement)`：

- `content` 为**字符串** → 纯文本消息。
- `content` 为**数组**（`[{type:"text",text:..},{type:"image_url",image_url:{url:"data:image/jpeg;base64,.."}}]`）→ 视觉消息（文字 + 多图）。

视图相关：`gradeWithImages`、`visionUserMessage`、`userMessageWithImages` 在此文件：`app/src/main/java/com/zsz/studyassistant/data/StudyAssistant.kt`。

---

## 4. 本机凭据文件 `LOCAL_SECRETS.txt`（仓库外，用户自管）

- 位置：工作区根 `E:\UserData\shizhen\文档\DeepSeekWorkSpace\LOCAL_SECRETS.txt`。
- **在 Git 仓库之外，绝不上传**；项目 `.gitignore` 也忽略了 `local_secrets*`/`LOCAL_SECRETS.txt`/`*.key`/`*.pem` 等。

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
