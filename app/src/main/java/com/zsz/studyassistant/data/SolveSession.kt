package com.zsz.studyassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜题记录（方案 B：新表隔离，见 DESIGN-20261013「② 搜题记录」）。
 *
 * 核心动机：**未点「存错题本」就退出 → 整个会话彻底丢失**（本仓库没有 `onCleared`/`SavedStateHandle`，
 * 只靠"离开页面时落库"挡不住进程被杀）。所以在 4 个生成入口的 `onDone` 里**尽早 insert 一条**，
 * 追问/继续生成再 UPDATE 同一条。
 *
 * 与 `questions` 表**完全隔离**：错题本/复习/统计/查重/备份的查询一律不碰这张表。
 *
 * ⚠️ **为什么单独一个文件**：`Question.kt` 里有 `@Dao`/`Migration`，把它整文件的 `*Kt` 类拉进来会连带
 * 加载 `androidx.room.migration.Migration`，而本机单元测试的 classpath 是手工拼的（不含 Room）→
 * 纯函数测试会 `NoClassDefFoundError`。裁剪策略的测试要构造本类，所以本类也必须与 Room 的
 * `@Dao`/`Migration` 分开。**这个文件只允许依赖 Room 的注解（`@Entity`/`@PrimaryKey`）**。
 */
@Entity(tableName = "solve_sessions")
data class SolveSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 来源：camera（拍照）/ ask（图文提问）/ text（文字）/ grade（批改） */
    val source: String = "camera",
    /** 题干（空则回退"（图片题）"，与 saveToNotebook 同规则） */
    val text: String = "",
    val answer: String = "",
    /** 与 questions.conversationJson 同一格式（ChatItem[] 的 JSON） */
    val conversationJson: String? = null,
    /** 单图（拍照）；纯文字题为 null */
    val imageBytes: ByteArray? = null,
    /** 多图（base64 数组的 JSON）；顺带修"多图拍题存错题本丢原图" */
    val imagesJson: String? = null,
    /** 已存进错题本时指向 questions.id */
    val savedQuestionId: Long? = null
)
