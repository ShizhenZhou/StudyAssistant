package com.zsz.studyassistant.data

/** A real handler event is bound to an installation, including same-version APK replacements. */
internal fun pendingCrashForInstallation(
    crashId: String?, crashInstallation: Long, currentInstallation: Long, acknowledgedId: String?
): String? = crashId?.takeIf {
    it.isNotBlank() && currentInstallation > 0 && crashInstallation == currentInstallation && it != acknowledgedId
}
