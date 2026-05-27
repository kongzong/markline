# Add project specific ProGuard rules here.

# --- Compose Runtime (minimum keeps for Compose to work) ---
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# --- Kotlin ---
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# --- Gson ---
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keep class app.markline.util.BackupData** { *; }

# --- App code ---
-keep class app.markline.** { *; }

# --- AndroidX Navigation (reflection-based) ---
-keep class androidx.navigation.** { *; }
