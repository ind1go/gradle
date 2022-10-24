plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Execution engine that takes a unit of work and makes it happen"

dependencies {
    api(project(":base-annotations"))
    api(project(":build-cache-base"))
    api(project(":build-operations"))
    api(project(":hashing"))
    api(project(":base-services"))
    api(project(":build-cache"))
    api(project(":core-api"))
    api(project(":functional"))
    api(project(":files"))
    api(project(":logging"))
    api(project(":messaging"))
    api(project(":model-core"))
    api(project(":persistent-cache"))
    api(project(":snapshots"))

    api(libs.jsr305)
    api(libs.slf4jApi)
    api(libs.guava)

    implementation(project(":problems"))

    implementation(libs.commonsLang)

    testImplementation(project(":native"))
    testImplementation(project(":logging"))
    testImplementation(project(":process-services"))
    testImplementation(project(":model-core"))
    testImplementation(project(":base-services-groovy"))
    testImplementation(project(":resources"))
    testImplementation(libs.commonsIo)
    testImplementation(testFixtures(project(":base-services")))
    testImplementation(testFixtures(project(":file-collections")))
    testImplementation(testFixtures(project(":messaging")))
    testImplementation(testFixtures(project(":snapshots")))
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":model-core")))

    testFixturesImplementation(libs.guava)
    testFixturesImplementation(project(":base-services"))
    testFixturesImplementation(project(":build-cache"))
    testFixturesImplementation(project(":snapshots"))
    testFixturesImplementation(project(":model-core"))

    integTestDistributionRuntimeOnly(project(":distributions-core"))
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
