# ===== R8 / ProGuard 规则（保守：优先保证功能与旧数据兼容）=====

# 保留注解、泛型签名、内部类（序列化 / 反射 / Room 需要）
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# 依赖库的告警静音（原有规则）
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.jetbrains.annotations.**

# ---------- kotlinx.serialization（会话 JSON、请求/响应）----------
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 本项目所有 @Serializable 类的 serializer 与 Companion 全部保留
-keep,includedescriptorclasses class com.zsz.studyassistant.**$$serializer { *; }
-keepclassmembers class com.zsz.studyassistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.zsz.studyassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 数据模型整体保留（字段名不能改，否则旧会话 JSON 无法反序列化）
-keep class com.zsz.studyassistant.MainViewModel$ChatItem { *; }
-keep class com.zsz.studyassistant.data.Question { *; }
-keep class com.zsz.studyassistant.data.Category { *; }
-keep class com.zsz.studyassistant.data.Tag { *; }
-keep class com.zsz.studyassistant.data.QuestionTag { *; }
-keep class com.zsz.studyassistant.data.Review { *; }
-keep class com.zsz.studyassistant.data.DeepSeekMessage { *; }
-keep class com.zsz.studyassistant.data.DeepSeekRequest { *; }
-keep class com.zsz.studyassistant.data.DeepSeekResponse { *; }

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---------- Retrofit ----------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---------- 本项目组件（Manifest / 系统按名调用）----------
-keep class com.zsz.studyassistant.MainActivity { *; }
-keep class com.zsz.studyassistant.data.AnswerForegroundService { *; }
-keep class com.zsz.studyassistant.data.ReminderReceiver { *; }
-keep interface com.zsz.studyassistant.data.DeepSeekService { *; }
