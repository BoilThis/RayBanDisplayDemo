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

        val localProperties = java.util.Properties()
        val localPropertiesFile = rootDir.resolve("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        // Meta Wearables Device Access Toolkit
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = ""
                password = localProperties.getProperty("github_token")
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("github_token").orNull
            }
        }
    }
}

rootProject.name = "RayBanDisplayDemo"
include(":app")