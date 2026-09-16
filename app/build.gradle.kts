import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

private val pinotSigningKeys = listOf(
    "pinot.keystore",
    "pinot.keyAlias",
    "pinot.keystorePassword",
    "pinot.keyPassword",
)

private fun loadLocalProperties(): Properties {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

private fun pinotProperty(props: Properties, key: String): String? =
    props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

private fun missingPinotSigningKeys(props: Properties = loadLocalProperties()): List<String> =
    pinotSigningKeys.filter { pinotProperty(props, it) == null }

android {
    namespace = "com.pinotrouge.messaging"
    // compileSdk is what we build against; minSdk is 33 and targetSdk stays at 36.
    // Bumped to 37 because androidx.hilt 1.4 / room 2.8 / work 2.11 declare it
    // as their minimum compile target. It does not change which devices can
    // install the app, nor which runtime behaviours we opt into.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.pinotrouge.messaging"
        minSdk = 33      // was 36
        targetSdk = 36   // unchanged
        // Incremented manually per release. Do not auto-bump.
        versionCode = 1
        versionName = "0.1.0"

        // Hilt instrumented runner — required for @HiltAndroidTest (and fine for the rest).
        testInstrumentationRunner = "com.pinotrouge.messaging.PinotTestRunner"
    }

    signingConfigs {
        create("release") {
            // Always a named release config so AGP cannot silently fall back to
            // the debug keystore. If any pinot.* key is missing, storeFile stays
            // unset and the task-graph check below fails naming the key.
            val props = loadLocalProperties()
            if (missingPinotSigningKeys(props).isEmpty()) {
                storeFile = file(pinotProperty(props, "pinot.keystore").orEmpty())
                keyAlias = pinotProperty(props, "pinot.keyAlias")
                storePassword = pinotProperty(props, "pinot.keystorePassword")
                keyPassword = pinotProperty(props, "pinot.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // AGP 9.3: enable turns on R8 and optimized resource shrinking.
            // The two flags below are the same decision in the legacy DSL.
            optimization {
                enable = true
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    // mockk-android pulls junit-jupiter jars that each ship META-INF/LICENSE.md.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
            )
        }
    }

    // Room schema export (feat/data-layer). Committed under app/schemas/.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // The filter engine. Pure Kotlin, no Android — see core/rules/build.gradle.kts.
    implementation(project(":core:rules"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.name.contains("Release") }
    if (!buildingRelease) return@whenReady
    val props = loadLocalProperties()
    val missing = missingPinotSigningKeys(props)
    if (missing.isNotEmpty()) {
        error(
            "Release signing requires these keys in local.properties: " +
                missing.joinToString(", ") { "`$it`" } +
                ". The release build will not fall back to the debug keystore.",
        )
    }
    val keystorePath = pinotProperty(props, "pinot.keystore").orEmpty()
    if (!rootProject.file(keystorePath).isFile) {
        error(
            "Release signing: `pinot.keystore` does not exist. " +
                "The release build will not fall back to the debug keystore.",
        )
    }
}
