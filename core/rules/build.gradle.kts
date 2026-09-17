import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain Kotlin/JVM module: no Android Gradle plugin, no
// `android.*` on the classpath. If you find yourself needing a Context, a
// Cursor, or System.currentTimeMillis() in here, the design is wrong — pass the
// value in. See README.md (Architecture).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Must match :app's compileOptions, or Gradle rejects the dependency.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.re2j)
    testImplementation(libs.junit)
}
