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

// drive_manager's files the browser needs too (the npm package carries them; the tests read them from here)
val serverResources = rootDir.resolve("../drive_manager/src/main/resources").canonicalFile
val serverTestResources = rootDir.resolve("../drive_manager/src/test/resources").canonicalFile

/**
 * The bot's resources (the server's class-path bot files: its levels, tuned settings and named models) in one JSON
 * document, for a game in the browser (EngineApi.installBotResources):
 * {"format": "eras-bot-resources/1", "files": {"/bot/models.txt": "<text>", ...}}
 */
val botResources by tasks.registering {
    val sources = fileTree(serverResources.resolve("bot"))
    val output = layout.buildDirectory.file("generated/browserAssets/bot-resources.json")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(output)
    doLast {
        val files = sources.files.sortedBy { it.name }
        val out = StringBuilder("{\"format\":\"eras-bot-resources/1\",\"files\":{")
        files.forEachIndexed { i, f ->
            if (i > 0) out.append(',')
            out.append(jsonString("/bot/" + f.name)).append(':').append(jsonString(f.readText(Charsets.UTF_8)))
        }
        out.append("}}\n")
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(out.toString(), Charsets.UTF_8)
    }
}

/**
 * The starter decks (ST1–ST4: each card's metadata, as drive_manager's tests seat them) as LocalMatch's decks, by deck id:
 * {"format": "eras-starter-decks/1", "decks": {"deck-st1": {"deckName": "ST1", "metadata": [<the cards' metadata>]}, ...}}
 */
val starterDecks by tasks.registering {
    val names = listOf("st1", "st2", "st3", "st4")
    val sources = names.map { serverTestResources.resolve("simulator/$it-cards.json") }
    val output = layout.buildDirectory.file("generated/browserAssets/starter-decks.json")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(output)
    doLast {
        val out = StringBuilder("{\"format\":\"eras-starter-decks/1\",\"decks\":{")
        names.forEachIndexed { i, name ->
            if (i > 0) out.append(',')
            out.append(jsonString("deck-$name")).append(":{\"deckName\":").append(jsonString(name.uppercase()))
                .append(",\"metadata\":").append(sources[i].readText(Charsets.UTF_8).trim()).append('}')
        }
        out.append("}}\n")
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(out.toString(), Charsets.UTF_8)
    }
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
        // Whole games against the bot run in the Node tests (LocalMatchTest): more than Mocha's default 2 s
        nodejs {
            testTask { useMocha { timeout = "900s" } }
        }
        // ES modules: what Angular's build (esbuild) bundles and optimizes; CommonJS would warn and bail out
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    // The npm package eras-web installs
    js().compilations["main"].packageJson {
        customField("name", "@eras/engine")
        customField("description", "The ERAS game engine (Kotlin Multiplatform) for the browser: LocalMatch, a game against the bot driven as the server is")
        customField("type", "module")
        customField("exports", mapOf(
            "." to mapOf("types" to "./eras-engine.d.mts", "import" to "./eras-engine.mjs"),
            "./card-bundle.json" to "./card-bundle.json",
            "./bot-resources.json" to "./bot-resources.json",
            "./starter-decks.json" to "./starter-decks.json",
            "./package.json" to "./package.json",
        ))
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
val botResourcesPath = layout.buildDirectory.file("generated/browserAssets/bot-resources.json").get().asFile.absolutePath
val starterDecksPath = layout.buildDirectory.file("generated/browserAssets/starter-decks.json").get().asFile.absolutePath
val decksPath = rootDir.resolve("../drive_manager/src/test/resources/simulator").canonicalPath
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    dependsOn(cardBundle, botResources, starterDecks)
    environment("ERAS_CARD_BUNDLE", bundlePath)
    environment("ERAS_BOT_RESOURCES", botResourcesPath)
    environment("ERAS_STARTER_DECKS", starterDecksPath)
    environment("ERAS_DECKS", decksPath)
    providers.environmentVariable("ERAS_TEST_DUMP").orNull?.let { environment("ERAS_TEST_DUMP", "$it/js") }
}

// `./gradlew jvmTest -PregenerateCardReader` rewrites the generated card reader (CardModelReaderGeneratorTest)
tasks.named<Test>("jvmTest") {
    dependsOn(cardBundle, botResources, starterDecks)
    environment("ERAS_CARD_BUNDLE", bundlePath)
    environment("ERAS_BOT_RESOURCES", botResourcesPath)
    environment("ERAS_STARTER_DECKS", starterDecksPath)
    environment("ERAS_DECKS", decksPath)
    providers.environmentVariable("ERAS_TEST_DUMP").orNull?.let { environment("ERAS_TEST_DUMP", "$it/jvm") }
    systemProperty("eras.regenerateCardReader", providers.gradleProperty("regenerateCardReader").isPresent.toString())
}

// The npm package @eras/engine (build/dist/js/productionLibrary): the ES modules and their TypeScript definitions, with
// what a game in the browser loads beside them: card-bundle.json (EngineApi.installCards), bot-resources.json
// (EngineApi.installBotResources) and starter-decks.json (LocalMatch's "decks" for the starter decks)
tasks.named<Copy>("jsBrowserProductionLibraryDistribution") {
    from(cardBundle, botResources, starterDecks)
    // the Node distribution (run by `build`) writes the same folder: let the browser one, with the assets, go last
    mustRunAfter("jsNodeProductionLibraryDistribution")
}

// `./gradlew jsSmoke`: plays a whole game with the npm package from Node, as eras-web's worker will (js-smoke/smoke.mjs)
val jsSmoke by tasks.registering(Exec::class) {
    dependsOn("jsBrowserProductionLibraryDistribution", "kotlinNodeJsSetup")
    mustRunAfter("jsNodeProductionLibraryDistribution")
    val dist = layout.buildDirectory.dir("dist/js/productionLibrary")
    inputs.dir(dist)
    inputs.file("js-smoke/smoke.mjs")
    workingDir = projectDir
    val node = providers.environmentVariable("NODE").orElse("node")
    commandLine(node.get(), "js-smoke/smoke.mjs", dist.get().asFile.absolutePath)
}
