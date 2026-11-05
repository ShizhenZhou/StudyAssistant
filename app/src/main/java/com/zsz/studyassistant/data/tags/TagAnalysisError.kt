package com.zsz.studyassistant.data

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.serialization.SerializationException

/** Expose only known message keys and an HTTP number; never forward exception messages or response bodies. */
data class TagAnalysisError(val key: String, val httpCode: Int? = null) {
    companion object {
        fun from(error: Exception, httpCode: Int? = null): TagAnalysisError {
            if (error is TagMergeException) return TagAnalysisError(error.messageKey)
            val key = when (httpCode) {
                400, 422 -> "tagMerge.analysisRequestRejected"
                401, 403 -> "tagMerge.analysisAuth"
                402 -> "tagMerge.analysisBalance"
                429 -> "tagMerge.analysisBusy"
                in 500..599 -> "tagMerge.analysisServer"
                null -> when (error) {
                    is InterruptedIOException -> "tagMerge.analysisTimeout"
                    is IOException -> "tagMerge.analysisNetwork"
                    is SerializationException -> "tagMerge.analysisDecode"
                    else -> "tagMerge.analysisFailed"
                }
                else -> "tagMerge.analysisHttp"
            }
            return TagAnalysisError(key, httpCode)
        }
    }
}
