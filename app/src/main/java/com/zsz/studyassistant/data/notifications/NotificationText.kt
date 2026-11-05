package com.zsz.studyassistant.data

import android.app.Notification
import android.os.Build
import android.text.SpannableString
import android.text.Spanned

/** The system chooses the visible color. Earlier versions display the same localized text. */
internal fun notificationText(text: String, caution: Boolean = false): CharSequence {
    if (Build.VERSION.SDK_INT < 37 || text.isEmpty()) return text
    val style = if (caution) Notification.SEMANTIC_STYLE_CAUTION else Notification.SEMANTIC_STYLE_INFO
    return SpannableString(text).apply {
        setSpan(Notification.createSemanticStyleAnnotation(style), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
