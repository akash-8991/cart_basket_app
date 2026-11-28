plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// SmartCart Test Store (D-027): a deliberately plain shop app that exists only so the automation runtime can
// be exercised end to end on emulators. It is never shipped and imitates no real partner app.
android {
    namespace = "com.smartcart.teststore"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.smartcart.teststore"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
