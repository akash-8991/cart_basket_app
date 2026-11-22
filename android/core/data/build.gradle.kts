plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Fixture tests read the shared contract fixtures directly (D-010).
tasks.withType<Test>().configureEach {
    systemProperty("fixturesDir", rootProject.file("../contracts/fixtures").absolutePath)
    inputs.dir(rootProject.file("../contracts/fixtures"))
}
