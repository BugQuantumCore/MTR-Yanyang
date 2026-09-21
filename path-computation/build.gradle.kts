plugins { `java-library` }

group = "org.mtr"
version = "1.0.0"

repositories { mavenCentral() }

java {
	toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
	withSourcesJar()
}

dependencies {
	testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
tasks.withType<AbstractArchiveTask>().configureEach {
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
}
