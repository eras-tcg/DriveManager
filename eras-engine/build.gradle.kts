plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    `maven-publish`
}

// The card files: the one copy of the programmed cards' data (cards/<edition>/<card>.json), read by the core's CardReader
val cardFiles = fileTree("cards") { include("**/*.json") }

/**
 * The card bundle: every card file's text in one JSON document, in the order of their paths, for the JVM
 * (eras/card-bundle.json in the jar, what the server installs) and for JavaScript (fetched by the site):
 * {"format": "eras-card-bundle/1", "files": [{"path": "E1/a-la-carga.json", "text": "<the file's text>"}, ...]}
 */
val cardBundle by tasks.registering {
    val sources = cardFiles
    val root = layout.projectDirectory.dir("cards")
    val output = layout.buildDirectory.file("generated/cardBundle/card-bundle.json")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(output)
    doLast {
        val base = root.asFile
        val files = sources.files.sortedBy { it.relativeTo(base).invariantSeparatorsPath }
        val out = StringBuilder("{\"format\":\"eras-card-bundle/1\",\"files\":[\n")
        files.forEachIndexed { i, f ->
            if (i > 0) out.append(",\n")
            out.append("{\"path\":").append(jsonString(f.relativeTo(base).invariantSeparatorsPath))
                .append(",\"text\":").append(jsonString(f.readText(Charsets.UTF_8))).append('}')
        }
        out.append("\n]}\n")
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(out.toString(), Charsets.UTF_8)
    }
}

/** A JSON string literal: quotes, backslashes and control characters escaped, the rest as is (UTF-8). */
fun jsonString(text: String): String {
    val out = StringBuilder("\"")
    for (c in text) when {
        c == '"' -> out.append("\\\"")
        c == '\\' -> out.append("\\\\")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> out.append(c)
    }
    return out.append('"').toString()
}

// The JVM artifact's resources: the card files where the server's class-path scanning finds them, and the bundle
val cardResources by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/cardResources"))
    from("cards") { include("**/*.json"); into("cards") }
    from(cardBundle) { into("eras") }
}
kotlin {
    // The core's platform seam (simulator/platform) is expect/actual classes
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    // The server: DriveManager (Java 21) uses the JVM artifact as a plain library
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            // Parameter names in the class files (as DriveManager compiled the core before): Jackson puts the properties
            // of a constructor's parameters first, in their order (CardSlot's JSON)
            javaParameters.set(true)
        }
    }
    // The site: eras-web imports the JavaScript artifact as an npm package, with TypeScript definitions
    js(IR) {
        outputModuleName.set("eras-engine")
        // The browser build is what eras-web bundles; its tests run on Node (the same JavaScript, no browser to download)
        browser {
            testTask { enabled = false }
        }
        nodejs()
        // ES modules: what Angular's build (esbuild) bundles and optimizes; CommonJS would warn and bail out
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    // The npm package eras-web installs
    js().compilations["main"].packageJson {
        customField("name", "@eras/engine")
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The card reader's generator (CardModelReaderGenerator) reads the model's Kotlin types
        jvmTest.dependencies {
            implementation(kotlin("reflect"))
        }
        // The card files as class-path resources (cards/<edition>/<card>.json) and their bundle, in the JVM artifact
        jvmMain {
            resources.srcDir(cardResources)
        }
    }
}


// The tests read the bundle from the build (on the JVM and on Node, the way the site will have it: its text), and the
// starter decks' metadata from drive_manager's test resources (the seeded bot game of SeededBotGameTest)
val bundlePath = layout.buildDirectory.file("generated/cardBundle/card-bundle.json").get().asFile.absolutePath
val decksPath = rootDir.resolve("../drive_manager/src/test/resources/simulator").canonicalPath
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    dependsOn(cardBundle)
    environment("ERAS_CARD_BUNDLE", bundlePath)
    environment("ERAS_DECKS", decksPath)
}

// `./gradlew jvmTest -PregenerateCardReader` rewrites the generated card reader (CardModelReaderGeneratorTest)
tasks.named<Test>("jvmTest") {
    dependsOn(cardBundle)
    environment("ERAS_CARD_BUNDLE", bundlePath)
    environment("ERAS_DECKS", decksPath)
    systemProperty("eras.regenerateCardReader", providers.gradleProperty("regenerateCardReader").isPresent.toString())
}
