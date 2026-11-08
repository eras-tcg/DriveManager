package com.drive.drive_manager.simulator

/** Node's file system (the tests run on Node, as ES modules: no `require`). */
private val fs: dynamic = js("process.getBuiltinModule('node:fs')")
private val env: dynamic = js("process.env")

actual object TestFiles {
    actual fun text(variable: String, child: String?): String {
        val path = env[variable] as String? ?: error("$variable is not set (the build sets it)")
        return fs.readFileSync(if (child == null) path else "$path/$child", "utf8") as String
    }

    actual fun dump(name: String, text: String) {
        val dir = env["ERAS_TEST_DUMP"] as String? ?: return
        fs.mkdirSync(dir, js("({recursive: true})"))
        fs.writeFileSync("$dir/$name", text, "utf8")
    }
}
