// The JVM CI job runs this module with --project-dir neural-core.
// The application's root settings still include the same module as :neural-core.
rootProject.name = "neural-core"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral {
            when (System.getenv("OMNI_MAVEN_CENTRAL_FALLBACK")) {
                "1" -> url = uri("https://repo1.maven.org/maven2/")
                "2" -> url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                else -> Unit
            }
        }
    }
}
