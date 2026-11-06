package com.zsz.studyassistant.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zsz.studyassistant.data.QuestionId
import com.zsz.studyassistant.data.QuestionIdentity
import com.zsz.studyassistant.data.StudyAssistant
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Owns crop state, detection and private recovery checkpoints for one ViewModel lifetime.
 * Completion is handed to the conversation owner; restoration never launches an AI answer.
 */
internal class CaptureFlowController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val onUsageChanged: () -> Unit,
    private val onComplete: (List<ByteArray>, Boolean) -> Unit
) {
    var captureIdentity: Pair<QuestionId, Long>? = null
    // ---- 框选流程：一次 1~2 张（拍题 / 批改 的「两张」模式，两张都各自过框选）----
    /** 待框选的图片路径（有序：单张 = 题目；两张 = 题目、我的作答） */
    var cropPaths by mutableStateOf<List<String>>(emptyList())
        private set
    /** 当前正在框选第几张（0 基） */
    var cropIndex by mutableStateOf(0)
        private set
    /** 本次流程是否属于批改（决定框选完成后走 startGrade 还是 solve） */
    var cropGrade by mutableStateOf(false)
        private set
    /** 期望张数：两张模式 = 2；其余 = 实际选择张数 */
    var cropExpect by mutableStateOf(1)
        private set
    /** 还差一张（两张模式只给了一张）→ 界面应回到拍摄界面继续拍/选 */
    var cropNeedsMore by mutableStateOf(false)
        private set
    private val cropResults = mutableListOf<ByteArray>()
    private val captureFlowStore by lazy { com.zsz.studyassistant.data.CaptureFlowStore(app) }
    private val captureRestoreMutex = kotlinx.coroutines.sync.Mutex()
    private var captureRecoveryChecked = false
    private var captureStartedAt = 0L
    private var captureFlowRevision = 0L
    private var captureWriteJob: kotlinx.coroutines.Job? = null
    var captureRecoveryNoticeKey by mutableStateOf<String?>(null)
        private set
    fun consumeCaptureRecoveryNotice() { captureRecoveryNoticeKey = null }

    /** Restore only the input/crop queue. Recovery must never start an AI request. */
    suspend fun restoreCaptureFlowIfNeeded() = captureRestoreMutex.withLock {
        if (captureRecoveryChecked || cropPaths.isNotEmpty()) return@withLock
        val revision = captureFlowRevision
        val restored = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captureFlowStore.read() }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) {
            if (revision == captureFlowRevision) captureRecoveryNoticeKey = "camera.recoveryReadFailed"
            null
        }
        if (revision != captureFlowRevision) return@withLock
        captureRecoveryChecked = true
        if (restored != null) {
            cropPaths = restored.paths
            cropIndex = restored.index
            cropGrade = restored.grade
            cropExpect = restored.expect
            cropNeedsMore = restored.needsMore
            cropResults.clear(); cropResults.addAll(restored.results)
            captureStartedAt = restored.createdAt
            captureIdentity = restored.questionId?.let { it to restored.createdAt }
        }
    }

    private fun checkpointCaptureFlow() {
        if (cropPaths.isEmpty()) return
        captureRecoveryChecked = true
        val revision = ++captureFlowRevision
        val ticket = captureFlowStore.reserve()
        val state = com.zsz.studyassistant.data.CaptureFlowState(cropPaths.toList(), cropIndex, cropGrade,
            cropExpect, cropNeedsMore, cropResults.toList(), captureIdentity?.first, captureStartedAt)
        captureWriteJob?.cancel()
        captureWriteJob = scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val job = kotlinx.coroutines.currentCoroutineContext()
                    captureFlowStore.write(state, ticket) { job.ensureActive() }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (revision == captureFlowRevision) captureRecoveryNoticeKey = "camera.recoveryWriteFailed"
            }
        }
    }

    /** Test/lifecycle callers can wait for the latest durable checkpoint without blocking Main. */
    internal suspend fun awaitCaptureCheckpoint() { captureWriteJob?.join() }


    val currentCropPath: String? get() = cropPaths.getOrNull(cropIndex)
    internal val cropRevision: Long get() = captureFlowRevision
    /**
     * 是否还有待框选的图。
     * 注意：submitCropResult 里"前进"是立刻发生的，所以提交完第一张后
     * currentCropPath 已经指向第二张 —— 这里就用它判断，不要再比较 index 与 size。
     */
    val cropHasNext: Boolean get() = currentCropPath != null
    val cropTotal: Int get() = cropPaths.size
    val cropPos: Int get() = cropIndex + 1

    private val selections = mutableMapOf<String, com.zsz.studyassistant.ui.CropSelection>()
    internal fun cropSelection(path: String) = selections.getOrPut(path) { com.zsz.studyassistant.ui.CropSelection() }

    /** Own detection in the capture flow, so replacing an Activity doesn't restart an AI request. */
    internal fun detectCrop(path: String, bounds: androidx.compose.ui.geometry.Rect, useAi: Boolean, timeout: Long,
                            manual: Boolean = false) {
        if (currentCropPath != path) return
        val region = cropSelection(path)
        if (!manual && (region.touched || region.autoJob != null)) return
        val target = if (manual && region.touched) region.fraction?.let {
            com.zsz.studyassistant.data.ImageAutoCrop.NormRect(it.left, it.top, it.width, it.height)
        } else null
        val answerSheet = cropGrade && cropIndex == 1
        val edit = if (manual) region.touch() else null
        region.autoJob = scope.launch {
            if (!manual) launch {
                val box = withContext(Dispatchers.Default) {
                    runCatching { com.zsz.studyassistant.data.ImageAutoCrop.detectQuestionRect(path) }.getOrNull()
                }
                region.suggest(box, ai = false, bounds)
            }
            if (useAi) {
                region.status = "crop.aiBusy"
                val result = try {
                    withContext(Dispatchers.IO) {
                        StudyAssistant.detectCrop(path, timeout, manual, target, answerSheet) {
                            com.zsz.studyassistant.data.UsageStats.add(app, it)
                        }
                    }
                } finally {
                    onUsageChanged()
                }
                if (currentCropPath != path || (edit != null && !region.isLatestEdit(edit))) return@launch
                result.box?.let { box ->
                    if (manual) region.applyManual(box, bounds) else region.suggest(box, ai = true, bounds)
                }
                region.status = result.status
            }
        }
    }

    private fun clearCropSelections() {
        selections.values.forEach { it.cancelDetection() }
        selections.clear()
    }

    fun startCropFlow(paths: List<String>, grade: Boolean, expect: Int = paths.size) {
        val firstTime = System.currentTimeMillis()
        captureStartedAt = firstTime
        captureIdentity = if (!grade) QuestionIdentity.create(firstTime) to firstTime else null
        clearCropSelections()
        cropPaths = paths.toList()
        cropIndex = 0
        cropGrade = grade
        cropExpect = maxOf(expect, paths.size).coerceAtLeast(1)
        cropNeedsMore = false
        cropResults.clear()
        checkpointCaptureFlow()
    }

    /** 两张模式下又拍到 / 选到第 2 张 */
    fun appendCropPath(path: String) {
        // ★ 只在"确实还缺一张"时追加（cropNeedsMore），并把索引指向**刚追加的这张**：
        //   否则框选页仍显示上一张（曾经：第一张提交后守卫直接 return，第二张永远进不来/显示错图）
        if (!cropNeedsMore) return
        cropPaths = cropPaths + path
        cropIndex = cropPaths.size - 1
        cropNeedsMore = false
        checkpointCaptureFlow()
    }

    /** 提交当前这张的框选结果：还有下一张就前进；否则凑够张数就进入解题/批改，不够就回拍摄界面 */
    fun submitCropResult(bytes: ByteArray) {
        selections[currentCropPath]?.cancelDetection()
        cropResults.add(bytes)
        if (cropIndex + 1 < cropPaths.size) {
            cropIndex += 1
        } else if (cropResults.size < cropExpect) {
            cropNeedsMore = true
        } else {
            finishCropFlow()
        }
        if (cropPaths.isNotEmpty()) checkpointCaptureFlow()
    }

    /** 长按「整张图片」：把剩下的（最多两张）全部按整图提交 */
    fun skipRemainingCrop(decode: (String) -> ByteArray?): Boolean {
        if (currentCropPath == null) return false
        // Commit the batch only after every input decoded; dropping one changes question/answer roles.
        val decoded = cropPaths.drop(cropIndex).map { decode(it)?.takeIf { bytes -> bytes.isNotEmpty() } ?: return false }
        selections[currentCropPath]?.cancelDetection()
        cropResults.addAll(decoded)
        cropIndex = cropPaths.size
        if (cropResults.size < cropExpect) { cropNeedsMore = true; checkpointCaptureFlow() } else finishCropFlow()
        return true
    }

    /** 两张模式：从第 2 张返回第 1 张重新框（丢弃第 1 张已提交的结果，保持队列一致） */
    fun cropGoBackOne() {
        if (cropIndex <= 0) return
        selections[currentCropPath]?.cancelDetection()
        if (cropResults.isNotEmpty()) cropResults.removeAt(cropResults.size - 1)
        cropIndex -= 1
        cropNeedsMore = false
        checkpointCaptureFlow()
    }

    fun cancelCropFlow() {
        val sharedSources = cropPaths.map { java.io.File(it) }.filter {
            it.parentFile == app.cacheDir && it.name.startsWith("shared_")
        }
        captureRecoveryChecked = true
        val revision = ++captureFlowRevision
        val ticket = captureFlowStore.reserve()
        captureWriteJob?.cancel()
        captureWriteJob = scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    captureFlowStore.clear(ticket)
                    sharedSources.forEach { it.delete() }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (revision == captureFlowRevision) captureRecoveryNoticeKey = "camera.recoveryClearFailed"
            }
        }
        clearCropSelections()
        cropPaths = emptyList()
        cropIndex = 0
        cropGrade = false
        cropExpect = 1
        cropNeedsMore = false
        cropResults.clear()
        captureIdentity = null
        captureStartedAt = 0L
    }

    private fun finishCropFlow() {
        val imgs = cropResults.toList()
        val grade = cropGrade
        val identity = captureIdentity
        cancelCropFlow()
        captureIdentity = identity
        if (imgs.isEmpty()) return
        onComplete(imgs, grade)
    }

}
