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

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.ki.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
