pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // SQLCipher for Android built against OpenSSL, which uses the CPU's AES instructions, from the GitHub
        // release of gitakoos/sqlcipher-android-builds. gradle/verification-metadata.xml pins its SHA-256.
        // A copy in third_party/sqlcipher, laid out like the release, is taken first.
        exclusiveContent {
            forRepositories(
                ivy {
                    name = "sqlcipherLocal"
                    url = settingsDir.resolve("third_party/sqlcipher").toURI()
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                },
                ivy {
                    name = "sqlcipherRelease"
                    url = uri("https://github.com/gitakoos/sqlcipher-android-builds/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                },
            )
            filter { includeModule("eu.akoos.sqlcipher", "sqlcipher-android") }
        }
    }
}

rootProject.name = "ProtonPhotos"
include(":app")
include(":baseline-profile")
