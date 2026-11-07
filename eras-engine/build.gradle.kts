plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    `maven-publish`
}

kotlin {
    // The server: DriveManager (Java 21) uses the JVM artifact as a plain library
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
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
    }
}
