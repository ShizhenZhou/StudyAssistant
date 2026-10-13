package com.zsz.studyassistant.data

/**
 * 搜题记录（`solve_sessions`）的**裁剪策略** —— 纯函数，单独一个文件。
 *
 * ⚠️ **为什么单独放一个文件、而不是写在 `Question.kt` 里**：
 *   本机的单元测试是**纯 JVM**（没有 room-testing / Robolectric），测试 classpath 是
 *   `tools/run-unit-tests.ps1` 手工拼的，**不含 Room 的 jar**。纯函数一旦和 `@Entity`/`@Dao`/
 *   `Migration` 放在同一个文件（同一个 `*Kt` 类），加载它就会去加载 `androidx.room.migration.Migration`
 *   → `NoClassDefFoundError`，测试全挂。同类坑还有 `CropScreenKt` 会因 `Color` 拉起 `ui-graphics`。
 *   所以：**这个文件只允许依赖基本类型**（不要加 Android / Room / Compose 的 import）。
 *
 * 设计取舍（见 DESIGN-20261013 §2.6 用户拍板：保留最近 200 条 / 30 天，保留原图）：
 *  - 记录里有 `imageBytes`(BLOB) 与 `conversationJson`（`ChatItem.images` 是 base64），不裁剪就是数据库无限膨胀；
 *  - 判定逻辑抽成纯函数 → 有回归网，"以后调参/改规则"不会把用户记录误删光。
 */

/** 保留条数上限（超出后按 `createdAt` 从旧到新删） */
const val SOLVE_SESSION_KEEP = 200

/** 保留天数上限（早于 `now - 30 天` 的一律删） */
const val SOLVE_SESSION_KEEP_DAYS = 30

/**
 * 该删哪些搜题记录。
 *
 * 输入用**轻查询**的结果（`QuestionDao.solveSessionsLightOnce()`；只含 id/createdAt 等轻字段，
 * 不含 BLOB 与 conversationJson），所以测试不需要 Room、不需要真机。
 *
 * 规则（两条任意命中即删）：
 *  - **条数**：按 `createdAt` 倒序，第 [keep] 条之后（更旧）的一律删；`keep <= 0` = 不按条数裁；
 *  - **时间**：`createdAt < cutoffTs` 的删；`cutoffTs <= 0` = 不按时间裁（调用方没给截止时间时别误删全部）。
 *
 * 与传入顺序无关：内部自己按 `createdAt` 倒序取"最新的 keep 条"。
 * 返回去重后的 id 列表，调用方直接拿去 `deleteSolveSessionsByIds`。
 */
fun solveSessionsToDelete(
    sessions: List<SolveSession>,
    cutoffTs: Long,
    keep: Int = SOLVE_SESSION_KEEP
): List<Long> {
    if (sessions.isEmpty()) return emptyList()
    val tooOld = if (cutoffTs > 0) sessions.filter { it.createdAt < cutoffTs } else emptyList()
    val overflow = if (keep > 0) sessions.sortedByDescending { it.createdAt }.drop(keep) else emptyList()
    return (tooOld + overflow).map { it.id }.distinct()
}
