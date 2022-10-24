plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Gradle Enterprise plugin dependencies that also need to be exposed to workers"

gradlebuildJava.usedInWorkers()

dependencies {
    api(libs.jsr305)
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
