import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer
import org.apache.tools.ant.filters.ReplaceTokens
import org.mtr.core.Generator
import org.mtr.core.WebserverSetup

plugins {
	java
	`maven-publish`
	id("io.freefair.lombok") version "+"
	id("com.gradleup.shadow") version "+"
}

group = "org.mtr"
version = project.version

repositories {
	mavenCentral()
	maven(url = "https://repo.mikeprimm.com/")
	flatDir { dirs("libs") }
}

dependencies {
	// Core
	implementation("com.google.code.gson:gson:+")
	implementation("it.unimi.dsi:fastutil:+")
	implementation("commons-io:commons-io:+")
	implementation("com.squareup.okhttp3:okhttp:+")
	implementation("org.eclipse.jetty:jetty-servlet:+")
	implementation("org.msgpack:msgpack-core:+")
	implementation("org.apache.logging.log4j:log4j-core:2.+")
	implementation("org.jspecify:jspecify:+")
	implementation("info.picocli:picocli:+")
	runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.+")

	// Compile-only
	compileOnly("xyz.jpenilla:squaremap-api:+")
	compileOnly("net.kyori:adventure-api:+")
	compileOnly("us.dynmap:DynmapCoreAPI:3.8")

	// Test
	testImplementation("org.junit.jupiter:junit-jupiter-api:+")
	testImplementation("org.junit.platform:junit-platform-launcher:+")
	testImplementation("org.apache.httpcomponents:httpclient:+")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:+")
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
	withSourcesJar()
	withJavadocJar()
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			artifact(tasks.shadowJar)
			artifact(tasks.named("sourcesJar"))
			artifact(tasks.named("javadocJar"))
		}
	}

	repositories {
		maven {
			name = "GitHubPackages"
			url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "owner/repo"}")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
	}
}

tasks {
	jar {
		manifest { attributes("Main-Class" to "org.mtr.core.Main") }
	}

	shadowJar {
		archiveClassifier.set("")
		mergeServiceFiles()
		transform(Log4j2PluginsCacheFileTransformer())
		minimize {
			exclude(dependency("com.google.code.gson:gson"))
			exclude(dependency("com.squareup.okhttp3:okhttp"))
			exclude(dependency("it.unimi.dsi:fastutil"))
			exclude(dependency("org.eclipse.jetty:jetty-servlet"))
			exclude(dependency("org.msgpack:msgpack-core"))
		}
		relocate("com", "org.mtr.libraries.com") { skipStringConstants = true }
		relocate("it", "org.mtr.libraries.it") { skipStringConstants = true }
		relocate("jakarta", "org.mtr.libraries.jakarta")
		relocate("kotlin", "org.mtr.libraries.kotlin") { skipStringConstants = true }
		relocate("okhttp3", "org.mtr.libraries.okhttp3") { skipStringConstants = true }
		relocate("okio", "org.mtr.libraries.okio") { skipStringConstants = true }
		relocate("org", "org.mtr.libraries.org") { skipStringConstants = true; exclude("org.mtr.**") }
		relocate("picocli", "org.mtr.libraries.picocli") { skipStringConstants = true }
	}

	withType<AbstractArchiveTask> {
		isPreserveFileTimestamps = false
		isReproducibleFileOrder = true
	}

	test {
		useJUnitPlatform()
		testLogging { showStandardStreams = true }
	}

	javadoc {
		// Suppress "missing" doclint only (generated classes don't need javadoc)
		(options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
	}

	val generateVersionTask = register("generateVersion") {
		description = "Generate the version files for Java and TypeScript"
		outputs.file("website/src/version.ts")
		outputs.file("src/main/java/org/mtr/core/Version.java")

		doLast {
			val tokens = mapOf("version" to version)
			copy {
				from("website/version-template.txt")
				into("website/src")
				filter(mapOf("tokens" to tokens), ReplaceTokens::class.java)
				rename { "version.ts" }
			}
			copy {
				from("src/main/VersionTemplate.java")
				into("src/main/java/org/mtr/core")
				filter(mapOf("tokens" to tokens), ReplaceTokens::class.java)
				rename { "Version.java" }
			}
		}
	}

	val generateSchemaClassesTask = register("generateSchemaClasses") {
		description = "Generate the schema classes"
		inputs.dir("buildSrc/src/main/resources/schema")
		outputs.dir("src/main/java/org/mtr/core/generated")
		outputs.dir("src/main/java/org/mtr/legacy/generated")
		outputs.dir("website/src/app/entity/generated")

		doLast {
			Generator.generateJava(project, "schema/data", "core/generated/data", "core.data", "core.simulation")
			Generator.generateJava(project, "schema/legacy", "legacy/generated/data", "core.data", "legacy.data")
			Generator.generateJava(project, "schema/integration", "core/generated/integration", "core.data", "core.integration")
			Generator.generateJava(project, "schema/map", "core/generated/map", "core.map")
			Generator.generateJava(project, "schema/oba", "core/generated/oba", "core.oba")
			Generator.generateJava(project, "schema/operation", "core/generated/operation", "core.data", "core.operation")
			Generator.generateTypeScript(project, "schema/map", "website/src/app/entity/generated")
		}
	}

	// Ensures the Transport System Map website has been built into website/dist/website/browser.
	// WebserverSetup.setup (called from the setupWebserver task) embeds every file in that directory into the
	// generated WebserverResources class. If the directory is missing, an empty WebserverResources is silently
	// generated and every mod shading this library serves a broken Transport System Map page.
	fun ensureWebsiteBuilt() {
		val websiteDir = File(project.rootDir, "website")
		val distBrowserDir = File(websiteDir, "dist/website/browser")
		if (File(distBrowserDir, "index.html").exists()) {
			return
		}

		println("Transport System Map website build output not found at $distBrowserDir, building it now...")
		// The website imports the generated version file and TypeScript entities, regenerate them before compiling the Angular app
		if (!File(websiteDir, "src/version.ts").exists()) {
			copy {
				from(File(websiteDir, "version-template.txt"))
				into(File(websiteDir, "src"))
				filter(mapOf("tokens" to mapOf("version" to version)), ReplaceTokens::class.java)
				rename { "version.ts" }
			}
		}
		Generator.generateTypeScript(project, "schema/map", "website/src/app/entity/generated")

		val npmCommand = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"
		try {
			project.exec {
				workingDir(websiteDir)
				commandLine(npmCommand, "install", "--no-audit", "--no-fund")
			}
			project.exec {
				workingDir(websiteDir)
				commandLine(npmCommand, "run", "build")
			}
		} catch (e: Exception) {
			throw GradleException(
				"Failed to build the Transport System Map website. " +
					"Please install Node.js, then run `npm install` and `npm run build` inside the `website` directory before building Transport-Simulation-Core. " +
					"Without the built website, the generated WebserverResources is empty and mods shading this library would ship a broken Transport System Map page.",
				e)
		}
		if (!File(distBrowserDir, "index.html").exists()) {
			throw GradleException(
				"The Transport System Map website build output is still missing ($distBrowserDir/index.html). " +
					"Run `npm install` and `npm run build` inside the `website` directory, then build again. " +
					"Without the built website, the generated WebserverResources is empty and mods shading this library would ship a broken Transport System Map page.")
		}
	}

	register("buildWebsite") {
		group = "build"
		description = "Builds the Transport System Map website (Angular) so it can be embedded into WebserverResources. Requires Node.js."
		doLast {
			ensureWebsiteBuilt()
		}
	}

	val setupWebserverTask = register("setupWebserver") {
		description = "Setup webserver"
		outputs.file("src/main/java/org/mtr/core/generated/WebserverResources.java")
		// Always regenerate: a previously generated (possibly empty) WebserverResources.java must never be
		// considered up-to-date, otherwise a stale class built without the website would silently persist
		outputs.upToDateWhen { false }

		doLast {
			// Build the website first (if not already built) so WebserverSetup embeds real resources instead of generating an empty class
			ensureWebsiteBuilt()
			WebserverSetup.setup(project.rootDir, "", "core")
		}
	}

	withType<JavaCompile> {
		options.compilerArgs.addAll(
			listOf(
				"-Xlint:all",
				"-Xlint:-serial",     // No Java serialization
				"-Xlint:-processing", // Lombok annotation processor noise
				"-Xlint:-this-escape" // Safe: schema constructor pattern
			)
		)

		dependsOn(generateVersionTask, generateSchemaClassesTask, setupWebserverTask)
	}

	named<Jar>("sourcesJar") {
		dependsOn(generateVersionTask, generateSchemaClassesTask, setupWebserverTask)
	}
}
