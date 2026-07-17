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

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.ki.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
