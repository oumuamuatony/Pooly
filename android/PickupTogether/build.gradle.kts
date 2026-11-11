plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

// Keep generated intermediates outside a potentially synced Documents folder.
// The shareable APK is exported back into together/artifacts by the app task.
val localBuildRoot = java.io.File(gradle.gradleUserHomeDir, "together-build/${rootProject.name}-${Integer.toHexString(rootDir.absolutePath.hashCode())}")
layout.buildDirectory.set(localBuildRoot.resolve("root"))
subprojects { layout.buildDirectory.set(localBuildRoot.resolve(name)) }
