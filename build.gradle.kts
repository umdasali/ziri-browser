// Top-level build file. AGP 9 has built-in Kotlin, so no kotlin-android plugin is applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
