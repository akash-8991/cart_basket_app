plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.smartcart"
version = "0.3.0"

kotlin { jvmToolchain(21) }

application { mainClass.set("com.smartcart.server.ApplicationKt") }

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.logback)
    implementation(libs.logstash)
    implementation(libs.micrometer.prometheus)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.java.jwt)
    implementation(libs.bouncycastle)
    implementation(libs.lettuce)
    implementation(libs.jackson.yaml)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.json.schema)
    testImplementation(libs.jackson.yaml)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.bom))
    testRuntimeOnly(libs.embedded.postgres.darwin.arm64)
    testRuntimeOnly(libs.embedded.postgres.linux.amd64)
}

val contractsDir = rootDir.resolve("../contracts").normalize()

// The flag catalogue is part of the frozen contract; ship it inside the server.
tasks.processResources {
    from(contractsDir.resolve("feature-flags.yaml")) { into("contracts") }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("contractsDir", contractsDir.absolutePath)
    inputs.dir(contractsDir).withPropertyName("contracts").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.named<JavaExec>("run") {
    systemProperty("contractsDir", contractsDir.absolutePath)
}
