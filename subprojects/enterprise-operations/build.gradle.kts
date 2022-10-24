plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Build operations consumed by the Gradle Enterprise plugin"

dependencies {
    api(project(":build-operations"))

    api(libs.jsr305)

    implementation(project(":base-annotations"))
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
