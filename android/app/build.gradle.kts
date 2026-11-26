plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.smartcart.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartcart.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mock"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Lets device tests save screenshots that AGP pulls into build/outputs (TestStorage).
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "MOCK_SCENARIOS", "true")
            // Phase 3.7: empty → fixture fakes (default, and all tests); set to talk to a backend, e.g.
            // ./gradlew installDebug -Psmartcart.backendUrl=http://10.0.2.2:8080 (the emulator's view of the host).
            buildConfigField("String", "BACKEND_URL", "\"${project.findProperty("smartcart.backendUrl") ?: ""}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "MOCK_SCENARIOS", "false")
            // Production hosts are configured per environment later; release builds accept only HTTPS.
            buildConfigField("String", "BACKEND_URL", "\"${project.findProperty("smartcart.releaseBackendUrl") ?: ""}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Contract fixtures are packaged as assets under fixtures/ (D-010).
    sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("generated/fixtureAssets"))
    // UI acceptance tests run both on the JVM (Robolectric) and on devices/emulators.
    sourceSets["test"].kotlin.srcDir("src/sharedTest/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/sharedTest/kotlin")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("fixturesDir", rootProject.file("../contracts/fixtures").absolutePath)
            it.systemProperty("robolectric.logging.enabled", "false")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Compose UI tests need the debug-only test activity, so host tests run on the debug variant.
androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        @Suppress("DEPRECATION")
        it.enableUnitTest = false
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val copyFixtures by tasks.registering(Copy::class) {
    from(rootProject.file("../contracts/fixtures"))
    into(layout.buildDirectory.dir("generated/fixtureAssets/fixtures"))
}
tasks.named("preBuild") { dependsOn(copyFixtures) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.accessibility)
    // ATF 4.x understands Compose virtual nodes; 3.1.2 (espresso default) only sees the host View.
    androidTestImplementation(libs.atf)
    androidTestImplementation(libs.test.storage)
    androidTestUtil(libs.test.services)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.robolectric.annotations) // @Config in shared tests is inert on devices
}
