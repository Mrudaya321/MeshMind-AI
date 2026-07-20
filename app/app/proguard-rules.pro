# Room persistence classes R8 optimization overrides
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * extends androidx.room.RoomDatabase
-keep class com.qualcomm.meshmind.database.EntitiesKt { *; }
-keep class com.qualcomm.meshmind.database.AppDatabase { *; }

# LiteRT (TensorFlow Lite) JNI runtime optimizations
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# Coroutine and serialization descriptor preservations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Prevent obfuscation of domain model classes
-keep class com.qualcomm.meshmind.models.** { *; }
-keep class com.qualcomm.meshmind.state.RoutingState$RouteRecord { *; }
