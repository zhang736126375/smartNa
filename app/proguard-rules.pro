# --- 通用 ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# --- Kotlin / Coroutines ---
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }

# --- AndroidX / ViewBinding ---
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(...);
}
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(...);
}

# --- 业务模型（Mock / 后续接口 DTO） ---
-keep class com.bingo.smartna.collector.data.model.** { *; }

# --- coresdk JNI ---
-keep class com.bingo.coresdk.** { *; }

# --- Orbbec Ego SDK ---
-keep class com.orbbec.obsensor.** { *; }
-dontwarn com.orbbec.obsensor.**

# --- 华为扫码 HMS ---
-keep class com.huawei.hms.** { *; }
-keep class com.huawei.hms.ml.** { *; }
-dontwarn com.huawei.hms.**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- UtilCodeX（base-common consumer 已保留，此处兜底） ---
-keep class com.blankj.utilcode.** { *; }
-dontwarn com.blankj.utilcode.**

# --- Parcelable / Serializable ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- 枚举 ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- R8 反射警告 ---
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
