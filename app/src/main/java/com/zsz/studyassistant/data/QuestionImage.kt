package com.zsz.studyassistant.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一道题的**原图**（DB v9 新增，一题多图的正式存储）。
 *
 * 设计要点（2026-10-16）：
 *  - 图片是**二进制大对象**，单独成表而不是塞进 `questions` 行 → 列表查询不会被大 BLOB 拖慢；
 *  - `position` 有序：**0 = 主图**（错题本卡片 / 复习自测优先用它），1.. = 其余原图；
 *  - `source` 仅用于诊断（`camera` / `direct` / `migrate` / `import`）；
 *  - **兼容**：`questions.imageBytes` 仍保留（旧数据 + 列表页用它做"主图"快速读取），
 *    读取一律「先新表 → 空则回落 `imageBytes` → 再回落 `conversationJson`」三层兜底，
 *    所以**迁移只做加法，任何一步没跑完也不会比以前更差**。
 */
@Entity(
    tableName = "question_images",
    indices = [Index(value = ["questionId", "position"])]
)
data class QuestionImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: Long,
    /** 0 = 主图；1.. = 其余（最多 2，与拍题上限 3 张一致） */
    val position: Int,
    /** 原始 JPEG 字节（不存 base64） */
    val bytes: ByteArray,
    /** camera / direct / migrate / import（诊断用） */
    val source: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
