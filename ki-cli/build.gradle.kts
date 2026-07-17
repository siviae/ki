plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ki-agent"))
    implementation(project(":ki-tui"))
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.ext)
    implementation(libs.kotlinx.coroutines.core)

    // Local session store: embedded SQLite (no Spring, no external DB).
    implementation(libs.sqlite.jdbc)
    // Config + ki.toml manifest + model catalog parsing (our own types → Jackson).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.toml)

    // Structured logging → .ki/logs/: kotlin-logging facade + logback backend.
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
    // Drive a fresh logback context in tests to validate the shipped logback.xml.
    testImplementation(libs.logback.classic)
}

application {
    mainClass.set("dev.ki.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
