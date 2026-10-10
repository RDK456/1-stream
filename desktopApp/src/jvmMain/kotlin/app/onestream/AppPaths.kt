package app.onestream

import java.io.File

const val APP_NAME = "1-stream"
/** Set from gradle (`app.version`) via -Donestream.version, so the installer and the app always agree. */
val APP_VERSION: String = System.getProperty("onestream.version") ?: "0.0.0-dev"

/** All user data lives under %APPDATA%\1-stream (or ~/.1-stream off Windows). */
object AppPaths {
    val root: File by lazy {
        val base = System.getenv("ONESTREAM_HOME")?.let(::File)
            ?: System.getenv("APPDATA")?.let { File(it, APP_NAME) }
            ?: File(System.getProperty("user.home"), ".$APP_NAME")
        base.also { it.mkdirs() }
    }
    val plugins: File get() = File(root, "plugins").also { it.mkdirs() }
    val data: File get() = File(root, "data.json")
}