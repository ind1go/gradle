plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Utilities for parsing command line arguments"

gradlebuildJava.usedInWorkers()

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
