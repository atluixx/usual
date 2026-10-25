buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Firebase Auth 25 uses Kotlin 2.4 metadata. Override AGP's older default.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}
