# kotlinx.serialization, Room, WorkManager, OkHttp and Firebase ship their own
# consumer rules. These keep our own wire DTOs' generated serializers intact.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.digitronics.chat.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.digitronics.chat.**$$serializer { *; }

# Workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
