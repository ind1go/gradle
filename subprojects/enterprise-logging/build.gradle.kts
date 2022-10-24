plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Logging API consumed by the Gradle Enterprise plugin"

gradlebuildJava.usedInWorkers()

dependencies {
    api(project(":logging-api"))
    api(project(":build-operations"))

    api(libs.jsr305)
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
