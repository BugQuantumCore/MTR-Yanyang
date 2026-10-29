repositories {
	// CI builds Transport-Simulation-Core from the Yanyang-Technology-Group
	// fork and publishes it here (publishToMavenLocal) so the build tools
	// resolve without GitHub Packages credentials.
	mavenLocal()
	mavenCentral()
	// The crowdin client is vendored under buildSrc/vendor (MIT — see the
	// vendored pom) so builds resolve it offline; jitpack stays only as a
	// fallback for future version bumps.
	maven { url = uri("vendor") }
	maven { url = uri("https://jitpack.io") }
	maven {
		url = uri("https://maven.pkg.github.com/Minecraft-Transit-Railway/Transport-Simulation-Core")
		credentials {
			username = providers.gradleProperty("gpr.user").getOrNull() ?: "github-actions"
			password = providers.gradleProperty("gpr.key").getOrNull() ?: System.getenv("GITHUB_TOKEN")
		}
	}
}

dependencies {
	implementation("com.google.code.gson:gson:+")
	// Pinned to the vendored 1.36.0 (was "+"): a dynamic version makes Gradle
	// list jitpack's maven-metadata.xml on every single build, and jitpack's
	// intermittent metadata 401s failed all four yanyang.9 build-matrix jobs
	// while nothing in this repository had changed.
	implementation("com.github.crowdin:crowdin-api-client-java:1.36.0")
	implementation("it.unimi.dsi:fastutil:+")
	implementation("commons-io:commons-io:2.+")
	implementation("org.apache.httpcomponents:httpmime:+")
	implementation("org.mtr:transport-simulation-core-build-tools:+")
}
