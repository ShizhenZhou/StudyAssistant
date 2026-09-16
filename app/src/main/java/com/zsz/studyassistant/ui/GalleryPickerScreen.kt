package com.zsz.studyassistant.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.StudyAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自建相册选择器（系统 Photo Picker 无法按「勾选顺序」编号，故自绘）。
 * - 点按图片 = 勾选/取消；勾选的图片右上角显示**序号**（1、2、3…，即先后顺序）
 * - 数量上限由 [MainViewModel.pickerMax] 决定；选好后把**有序** URI 列表交回调用页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPickerScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val maxPick = vm.pickerMax

    var granted by remember { mutableStateOf(hasImagePermission(context)) }
    var items by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 选择顺序 = 点按先后（列表本身有序）
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.any { it } || hasImagePermission(context) }

    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(imagePermissions())
    }
    LaunchedEffect(granted) {
        if (granted) {
            items = withContext(Dispatchers.IO) { loadRecentImages(context) }
            loading = false
        } else {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s["picker.title"], maxLines = 1)
                        Text(
                            s.format("picker.maxHint", "n" to "$maxPick"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("✕", fontSize = 18.sp) }
                },
                actions = {
                    TextButton(
                        onClick = {
                            vm.setPickResult(selected)
                            nav.popBackStack()
                        },
                        enabled = selected.isNotEmpty()
                    ) {
                        Text(s.format("picker.done", "n" to "${selected.size}"), fontSize = 14.sp)
                    }
                }
            )
        }
    ) { padding ->
        when {
            !granted -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(s["picker.needPerm"], textAlign = TextAlign.Center)
                    Button(
                        onClick = { permLauncher.launch(imagePermissions()) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text(s["picker.grant"]) }
                }
            }
            loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(s["solve.thinking"], color = MaterialTheme.colorScheme.outline)
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(s["picker.empty"], color = MaterialTheme.colorScheme.outline)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(items) { uriStr ->
                        val order = selected.indexOf(uriStr)
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .padding(3.dp)
                                .clickable {
                                    selected = when {
                                        order >= 0 -> selected.filterNot { it == uriStr }
                                        selected.size < maxPick -> selected + uriStr
                                        else -> selected      // 已达上限：忽略（不改动已选顺序）
                                    }
                                }
                        ) {
                            Thumb(uriStr)
                            // 勾选序号徽标：数字 = 第几个被选中
                            if (order >= 0) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(Color(0x33000000))
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF1E88E5),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "${order + 1}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(uriStr: String) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, uriStr) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriStr)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver,
                        ContentUris.parseId(uri),
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** 是否有读取图片的权限（Android 13+ 用 READ_MEDIA_IMAGES，以下用 READ_EXTERNAL_STORAGE） */
private fun hasImagePermission(context: Context): Boolean =
    imagePermissions().any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

private fun imagePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** 读取最近的图片（按加入时间倒序，最多 300 张） */
private fun loadRecentImages(context: Context, limit: Int = 300): List<String> {
    val out = ArrayList<String>()
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID)
    runCatching {
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && out.size < limit) {
                out.add(ContentUris.withAppendedId(collection, c.getLong(idCol)).toString())
            }
        }
    }
    return out
}

/** 选中的 URI → 缓存临时文件（各页面统一入口；失败返回 null） */
internal fun uriToTempFile(context: Context, uriStr: String, prefix: String = "pick"): java.io.File? =
    runCatching {
        val file = java.io.File.createTempFile(prefix, ".jpg", context.cacheDir)
        context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file
    }.getOrNull()

/** 选中的 URI → 压缩后的 JPEG 字节（解题/批改/追问统一用） */
internal fun uriToCompressedBytes(context: Context, uriStr: String): ByteArray? =
    uriToTempFile(context, uriStr)?.let { runCatching { StudyAssistant.compressImage(it) }.getOrNull() }
