package com.drive.drive_manager.simulator

import java.io.File

actual object TestFiles {
    actual fun text(variable: String, child: String?): String {
        val path = System.getenv(variable) ?: error("$variable is not set (the build sets it)")
        return (if (child == null) File(path) else File(path, child)).readText(Charsets.UTF_8)
    }

    actual fun dump(name: String, text: String) {
        val dir = System.getenv("ERAS_TEST_DUMP") ?: return
        File(dir).mkdirs()
        File(dir, name).writeText(text, Charsets.UTF_8)
    }
}
