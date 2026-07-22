rootProject.name = "ki"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":ki-agent", ":ki-tui", ":ki-cli", ":ki-spring")
