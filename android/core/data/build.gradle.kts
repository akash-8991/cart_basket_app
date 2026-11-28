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
    // Phase 4.2: cart tasks run on the phone through the automation runtime.
    api(project(":core:automation"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:automation")))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Fixture tests read the shared contract fixtures directly (D-010). Backend integration tests run only
// when a backend URL is given: ./gradlew :core:data:test -PbackendUrl=http://127.0.0.1:8080
tasks.withType<Test>().configureEach {
    systemProperty("fixturesDir", rootProject.file("../contracts/fixtures").absolutePath)
    (project.findProperty("backendUrl") as String?)?.let { systemProperty("backendUrl", it) }
    (project.findProperty("adminToken") as String?)?.let { systemProperty("adminToken", it) }
    inputs.dir(rootProject.file("../contracts/fixtures"))
}
