package com.zsz.studyassistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/** Application artwork belongs to the notification card; small line icons still identify live chips. */
internal object NotificationArtwork {
    @Volatile private var cached: Bitmap? = null

    @Synchronized fun application(context: Context): Bitmap? {
        cached?.let { return it }
        val drawable = ContextCompat.getDrawable(context, com.zsz.studyassistant.R.mipmap.ic_launcher) ?: return null
        return Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, it.width, it.height)
            drawable.draw(Canvas(it))
            cached = it
        }
    }
}
