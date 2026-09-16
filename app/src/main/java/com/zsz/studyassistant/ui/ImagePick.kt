package com.zsz.studyassistant.ui

import android.content.Context
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.zsz.studyassistant.data.StudyAssistant
import java.io.File

/**
 * 系统相册选择请求。
 *
 * [ordered] = true 时开启**有序选择**（框架常量 `MediaStore.EXTRA_PICK_IMAGES_IN_ORDER`）：
 * 系统选择器会让用户控制勾选顺序并**按该顺序**把 URI 返回给 App（界面上会显示序号）。
 * 需 androidx.activity ≥ 1.10 且设备选择器支持（Android 15/16 ✅）；旧设备会自动忽略该参数。
 */
internal fun imagePickRequest(ordered: Boolean = true, maxItems: Int? = null): PickVisualMediaRequest =
    PickVisualMediaRequest.Builder()
        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
        .apply { if (maxItems != null && maxItems >= 2) setMaxItems(maxItems) }
        .setOrderedSelection(ordered)
        .build()

/** 选中的 URI → 缓存临时文件（各页面统一入口；失败返回 null） */
internal fun uriToTempFile(context: Context, uri: Uri, prefix: String = "pick"): File? =
    runCatching {
        val file = File.createTempFile(prefix, ".jpg", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file
    }.getOrNull()

/** 选中的 URI → 压缩后的 JPEG 字节（解题/批改/追问统一用） */
internal fun uriToCompressedBytes(context: Context, uri: Uri): ByteArray? =
    uriToTempFile(context, uri)?.let { runCatching { StudyAssistant.compressImage(it) }.getOrNull() }
