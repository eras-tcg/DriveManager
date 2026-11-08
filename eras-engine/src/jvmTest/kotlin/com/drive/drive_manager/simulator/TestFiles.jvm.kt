package com.drive.drive_manager.simulator

import java.io.File

actual object TestFiles {
    actual fun text(variable: String, child: String?): String {
        val path = System.getenv(variable) ?: error("$variable is not set (the build sets it)")
        return (if (child == null) File(path) else File(path, child)).readText(Charsets.UTF_8)
    }
}
