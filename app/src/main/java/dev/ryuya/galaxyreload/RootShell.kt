package dev.ryuya.galaxyreload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmRecord

/* Fixed commands only. Both process lifetime and captured output are bounded. */
class RootShell {
    class Denied : IOException()

    class CommandFailed : IOException()

    class TimedOut : IOException()

    @JvmRecord
    data class Outcome(val code: Int, val text: String) {
        override fun toString(): String = "Outcome[code=$code, text=$text]"
    }

    companion object {
        @Throws(Exception::class)
        fun requireRoot() {
            try {
                if (RootShell.run("exec /system/bin/id -u").trim { it <= ' ' } != "0")
                    throw RootShell.Denied()
            } catch (denied: RootShell.CommandFailed) {
                throw RootShell.Denied()
            }
        }

        @Throws(Exception::class)
        fun run(command: String?): String {
            val outcome = RootShell.execute(command)
            if (outcome.code !== 0) throw RootShell.CommandFailed()
            return outcome.text
        }

        @Throws(Exception::class)
        fun execute(command: String?): RootShell.Outcome {
            val process: Process?
            try {
                // App data isolation survives a plain su. Use KernelSU's global mount namespace so
                // the
                // helper sees the real SystemUI private directory rather than this app's isolated
                // mounts.
                process =
                    ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start()
            } catch (missing: IOException) {
                throw RootShell.Denied()
            }
            val reader = Executors.newSingleThreadExecutor()
            val output =
                reader.submit<String>(
                    Callable {
                        process.getInputStream().use { input ->
                            ByteArrayOutputStream().use { result ->
                                val buffer = ByteArray(1024)
                                var count: Int
                                while ((input.read(buffer).also { count = it }) != -1) {
                                    if (result.size() + count > 16384)
                                        throw IOException("Command output too large")
                                    result.write(buffer, 0, count)
                                }
                                return@Callable result.toString(StandardCharsets.UTF_8)
                            }
                        }
                    }
                )
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) throw RootShell.TimedOut()
                val text = output.get(2, TimeUnit.SECONDS)
                return RootShell.Outcome(process.exitValue(), text)
            } finally {
                process.destroyForcibly()
                reader.shutdownNow()
            }
        }
    }
}
