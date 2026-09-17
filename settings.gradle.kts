pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    components {
        // AGP / Jetifier / bundletool still publish the unpatched transitives.
        // Rewrite those edges when this build resolves those modules.
        withModule("com.android.tools.build:bundletool") {
            allVariants {
                withDependencies {
                    removeAll { it.group == "org.bitbucket.b_c" && it.name == "jose4j" }
                    add("org.bitbucket.b_c:jose4j:0.9.6")
                }
            }
        }
        withModule("com.android.tools.build.jetifier:jetifier-processor") {
            allVariants {
                withDependencies {
                    removeAll { it.group == "org.jdom" && it.name == "jdom2" }
                    add("org.jdom:jdom2:2.0.6.1")
                }
            }
        }
        withModule("org.apache.commons:commons-compress") {
            allVariants {
                withDependencies {
                    removeAll { it.group == "org.apache.commons" && it.name == "commons-lang3" }
                    add("org.apache.commons:commons-lang3:3.18.0")
                }
            }
        }
        withModule("org.apache.httpcomponents:httpmime") {
            allVariants {
                withDependencies {
                    removeAll { it.group == "org.apache.httpcomponents" && it.name == "httpclient" }
                    add("org.apache.httpcomponents:httpclient:4.5.14")
                }
            }
        }
    }
}

rootProject.name = "PinotRouge"
include(":app")

// Pure Kotlin/JVM. The filter engine lives here so that "the rule engine must
// not touch Android" is a compile error rather than a review comment, and so
// its tests run in seconds with no emulator.
include(":core:rules")
 