// Pins for Dependabot alerts that enter through AGP / Jetifier / bundletool,
// not the app runtime. Applied to every project configuration that resolves
// those coordinates. Do not add these libraries to :app implementation.
val buildClasspathPins = mapOf(
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.jdom:jdom2" to "2.0.6.1",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.bouncycastle:bcpkix-jdk18on" to "1.84",
    "org.bouncycastle:bcprov-jdk18on" to "1.84",
    "org.bouncycastle:bcutil-jdk18on" to "1.84",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
    "org.apache.httpcomponents:httpmime" to "4.5.14",
)

fun org.gradle.api.artifacts.ResolutionStrategy.pinBuildClasspathAdvisories() {
    eachDependency {
        val key = "${requested.group}:${requested.name}"
        val pinned = buildClasspathPins[key] ?: return@eachDependency
        if (requested.name == "httpclient" || requested.name == "httpmime") {
            val current = requested.version
            if (current != null && !current.startsWith("4.")) return@eachDependency
        }
        useVersion(pinned)
        because("Pinot Rouge build-classpath advisory pin ($key)")
    }
}

allprojects {
    buildscript {
        configurations.classpath {
            resolutionStrategy.pinBuildClasspathAdvisories()
        }
    }
    configurations.configureEach {
        resolutionStrategy.pinBuildClasspathAdvisories()
    }
}
