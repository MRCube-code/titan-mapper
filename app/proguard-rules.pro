# Titan Mapper ProGuard rules

# Keep kotlinx.serialization polymorphic discriminators
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# Keep domain model sealed interfaces/classes (serialized to/from JSON)
-keep class com.titanmapper.core.model.** { *; }

# Keep Hilt-generated components
-keep class dagger.hilt.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltModules* { *; }
-keep class **_ComponentTreeDeps* { *; }

# Keep Room entity/DAO classes
-keep class com.titanmapper.core.database.** { *; }

# Keep Shizuku AIDL stubs
-keep class rikka.shizuku.** { *; }

# Keep libsu Shell API
-keep class com.topjohnwu.superuser.** { *; }

# Keep HiddenApiBypass
-keep class org.lsposed.hiddenapibypass.** { *; }

# WorkManager worker classes
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# Composable functions are referenced by name via reflection in some tooling
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
