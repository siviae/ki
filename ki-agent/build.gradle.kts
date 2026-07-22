plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // ki-ai was merged into this module; its deps live here now (koog + coroutines +
    // serialization). The LLM layer keeps its `dev.ki.ai` package.
    api(libs.koog.agents)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.koog.agents.ext)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nuprocess)

    // Kotlin scripting host: compile .kts tool scripts on startup.
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
