plugins {
    id("lib-android")
}

android {
    sourceSets.named("test") {
        java.directories.clear()
        java.directories.add("test/java")
        kotlin.directories.clear()
        kotlin.directories.add("test/kotlin")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit"))
}

// Compatibility: some scripts request `testClasses` (Java plugin lifecycle).
// Android library modules don't provide `testClasses`, so expose a no-op task
// to avoid failing when that task name is referenced externally.
tasks.register("testClasses") {
    group = "verification"
    description = "Compatibility no-op for testClasses (Android plugin uses different test tasks)."
}
