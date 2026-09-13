package com.zsz.studyassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore（硬件级、系统加密）加密保存 DeepSeek API Key。
 * 密钥不出设备，密文存 SharedPreferences；任何人拿到 prefs 文件也无法还原明文。
 */
object ApiKeyStore {
    private const val PREFS_NAME = "secure_settings"
    private const val KEY_ALIAS = "deepseek_api_key"
    private const val PREFS_KEY = "deepseek_ciphertext"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun saveKey(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
        // 同步更新内存缓存，避免下次请求再解密
        KeyManager.setCachedKey(apiKey)
    }

    fun getKey(context: Context): String? {
        val b64 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY, null) ?: return null
        return try {
            val combined = Base64.decode(b64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LEN) return null
            val iv = combined.copyOfRange(0, GCM_IV_LEN)
            val ct = combined.copyOfRange(GCM_IV_LEN, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun hasKey(context: Context): Boolean = !getKey(context).isNullOrBlank()
}

/**
 * 运行时密钥提供者：允许 ApiClient 在每次请求时读取用户最新填写的 key
 */
object KeyManager {
    @Volatile private var appContext: Context? = null
    @Volatile private var cached: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 带内存缓存：只在首次（或保存后）解密一次 Keystore，避免每次请求都解密 */
    fun getApiKey(): String {
        cached?.let { return it }
        val k = appContext?.let { ApiKeyStore.getKey(it).orEmpty() } ?: ""
        cached = k
        return k
    }

    fun setCachedKey(k: String) {
        cached = k
    }
}
