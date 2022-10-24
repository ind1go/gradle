plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Logging infrastructure"

gradlebuildJava.usedInWorkers()

dependencies {
    api(project(":logging-api"))
    api(project(":enterprise-logging"))
    api(project(":base-annotations"))
    api(project(":build-operations"))

    api(libs.jansi)
    api(libs.jsr305)
    api(libs.slf4jApi)

    api(project(":base-services"))
    api(project(":enterprise-workers"))
    api(project(":messaging"))
    api(project(":cli"))
    api(project(":build-option"))
    api(project(":native"))

    implementation(libs.julToSlf4j)
    implementation(libs.ant)
    implementation(libs.commonsLang)
    implementation(libs.commonsIo)
    implementation(libs.guava)

    runtimeOnly(libs.log4jToSlf4j)
    runtimeOnly(libs.jclToSlf4j)

    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":testing-jvm")))
    testImplementation(libs.groovyDatetime)
    testImplementation(libs.groovyDateUtil)

    integTestImplementation(libs.ansiControlSequenceUtil)

    testFixturesImplementation(project(":base-services"))
    testFixturesImplementation(project(":enterprise-workers"))
    testFixturesImplementation(testFixtures(project(":core")))
    testFixturesImplementation(libs.slf4jApi)

    integTestDistributionRuntimeOnly(project(":distributions-core"))
}

packageCycles {
    excludePatterns.add("org/gradle/internal/featurelifecycle/**")
    excludePatterns.add("org/gradle/util/**")
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
