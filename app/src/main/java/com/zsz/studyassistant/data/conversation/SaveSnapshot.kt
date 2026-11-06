package com.zsz.studyassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Capture immutable inputs before constructing this; encoding runs off the UI thread.
 * Successful preparation is reused by persistence, retry and rescue export.
 * A preparation failure leaves the frozen inputs available for an explicit retry.
 */
internal class SaveSnapshot<T>(val id: QuestionId, prepare: () -> T) {
    private val prepared by lazy(prepare)
    suspend fun value(): T = withContext(Dispatchers.Default) { prepared }
}
