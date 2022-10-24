plugins {
    id("gradlebuild.distribution.implementation-java")
}

description = "Infrastructure that bootstraps a worker process"

gradlebuildJava.usedInWorkers()

dependencies {
    api(project(":logging-api"))
    api(project(":base-services"))
    api(project(":logging"))
    api(project(":messaging"))
    api(project(":process-services"))

    implementation(project(":base-annotations"))
    implementation(project(":enterprise-logging"))
    implementation(project(":native"))

    implementation(libs.slf4jApi)

    testImplementation(testFixtures(project(":core")))
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
