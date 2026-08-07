plugins {
    alias(libs.plugins.android.application) apply false
    // NOTE: no org.jetbrains.kotlin.android here — AGP 9 has built-in Kotlin
    // (runtime KGP dependency); applying kotlin-android is an error.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
