rootProject.name = "ki"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":ki-ai", ":ki-agent", ":ki-cli")
