package app.onestream.update

import java.io.File

/**
 * Applies a staged update after the app has exited. Windows locks running jars and the exe, so a
 * detached cmd script waits for the exe to be writable, swaps the files (or runs msiexec /qn), then optionally relaunches.
 */
object Applier {
    fun script(staged: Staged, installDir: File, exeName: String, relaunch: Boolean): String {
        val dst = installDir.absolutePath
        val body = when (staged) {
            is Staged.Delta -> buildString {
                appendLine("""%SystemRoot%\System32\xcopy.exe "${staged.dir.absolutePath}\*" "$dst\" /E /Y /I /Q >nul""")
                staged.removed.forEach { appendLine("""del /f /q "$dst\${it.replace('/', '\\')}" 2>nul""") }
            }
            is Staged.Full -> """%SystemRoot%\System32\msiexec.exe /i "${staged.msi.absolutePath}" /qn /norestart""" + "\n"
        }
        return buildString {
            appendLine("@echo off")
            appendLine("set n=0")
            appendLine(":wait")
            appendLine("set /a n+=1")
            appendLine("if %n% gtr 90 goto go")
            appendLine("""(>>"$dst\$exeName" (call )) 2>nul || (%SystemRoot%\System32\ping.exe -n 2 127.0.0.1 >nul & goto wait)""")
            appendLine(":go")
            append(body)
            if (relaunch) appendLine("""start "" "$dst\$exeName"""")
            appendLine("exit")
        }
    }

    /** Writes the script next to the staged files and starts it detached. */
    fun launch(staged: Staged, installDir: File, exeName: String, relaunch: Boolean, stageDir: File): File {
        val file = File(stageDir, "apply.cmd")
        file.writeText(script(staged, installDir, exeName, relaunch))
        ProcessBuilder(System.getenv("SystemRoot") + "\\System32\\cmd.exe", "/c", "start", "", "/min", file.absolutePath).start()
        return file
    }
}
