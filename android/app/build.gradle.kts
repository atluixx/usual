plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.okoda.usual"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.okoda.usual"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("live") {
            dimension = "environment"
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "false")
        }
        create("emulator") {
            dimension = "environment"
            applicationIdSuffix = ".emulator"
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

androidComponents {
    beforeVariants(selector().withFlavor("environment" to "emulator").withBuildType("release")) {
        it.enable = false
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.google.id)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
