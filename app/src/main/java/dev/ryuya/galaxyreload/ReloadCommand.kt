package dev.ryuya.galaxyreload

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.system.Os
import android.util.Log
import java.io.IOException
import java.lang.reflect.Method
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.JvmStatic

/* Root-only command entry. All targets and shell commands are fixed. */
class ReloadCommand {
    private lateinit var context: Context
    private var boot = 0
    private var hostUid = 0

    @Throws(Exception::class)
    private fun check(): String? {
        if (!ReloadCommand.firmwareSupported()) return "UNSUPPORTED"
        if (!trustedCompanion()) return "MODULE_REQUIRED"
        val unlocked = unlocked()
        if (unlocked != "OK") return unlocked
        val pid = systemUiPid()
        if (pid <= 0) return "NO_SYSTEMUI"
        val support = ReloadCommand.read(ReloadCommand.HOST.resolve(ReloadTicket.SUPPORT))
        return if (matchesSupport(support, pid)) "READY" else "SETUP"
    }

    /** Reload while retaining the native lock screen; never creates an unlock handover. */
    @Throws(Exception::class)
    private fun reloadLocked(): String? {
        if (!firmwareSupported()) return "UNSUPPORTED"
        if (!trustedCompanion()) return "MODULE_REQUIRED"
        lockedReloadRejection()?.let {
            return it
        }
        FileChannel.open(
                HOST.resolve("galaxy-reload-operation.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            .use { channel ->
                channel.tryLock().use { lock ->
                    if (lock == null) return "BUSY"
                    val pid = systemUiPid()
                    if (pid <= 0 || !isSystemUi(pid)) return "NO_SYSTEMUI"
                    lockedReloadRejection()?.let {
                        return it
                    }
                    // An interrupted normal reload must not supply a stale unlock request.
                    for (name in
                        arrayOf(
                            ReloadTicket.REQUEST,
                            ReloadTicket.RESULT,
                            ReloadTicket.CANCEL,
                        )) Files.deleteIfExists(HOST.resolve(name))
                    if (!isSystemUi(pid)) return "STATE_CHANGED"
                    Process.sendSignal(pid, 15)
                    val deadline = SystemClock.elapsedRealtime() + 20000
                    var candidate = 0
                    var stableSince = 0L
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val current = systemUiPid()
                        val now = SystemClock.elapsedRealtime()
                        if (current > 0 && current != pid && current == candidate) {
                            if (now - stableSince >= 3000 && lockedReloadRejection() == null)
                                return "RELOADED_LOCKED"
                        } else {
                            candidate = if (current != pid) current else 0
                            stableSince = now
                        }
                        SystemClock.sleep(100)
                    }
                    return "LOCKED_RELOAD_TIMEOUT"
                }
            }
    }

    private fun lockedReloadRejection(): String? {
        val user =
            (invokeStatic(Class.forName("android.app.ActivityManager"), "getCurrentUser") as Number)
                .toInt()
        val credentialReady =
            boot >= 0 && context.getSystemService(UserManager::class.java).isUserUnlocked()
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive()
        val window = service("window", "android.view.IWindowManager\$Stub")
        val trust = service("trust", "android.app.trust.ITrustManager\$Stub")
        val locked =
            java.lang.Boolean.TRUE == invoke(window, "isKeyguardLocked") &&
                java.lang.Boolean.TRUE == invoke(trust, "isDeviceLocked", 0, 0)
        return LockedReloadPolicy.rejection(user, credentialReady, interactive, locked)
    }

    @Throws(Exception::class)
    private fun reload(): String? {
        val readiness = check()
        if (readiness != "READY" && readiness != "SETUP") return readiness
        FileChannel.open(
                ReloadCommand.HOST.resolve("galaxy-reload-operation.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            .use { channel ->
                channel.tryLock().use { lock ->
                    if (lock == null) return "BUSY"
                    val pid = systemUiPid()
                    if (pid <= 0 || !isSystemUi(pid)) return "NO_SYSTEMUI"
                    val preflight = unlocked()
                    if (preflight != "OK") return preflight
                    val ticket =
                        ReloadTicket(
                            boot,
                            0,
                            SystemClock.uptimeMillis(),
                            UUID.randomUUID().toString(),
                        )
                    val authorized = AtomicBoolean(true)
                    LocalServerSocket("galaxy-reload-" + ticket.nonce).use { broker ->
                        val receiver =
                            Thread(
                                Runnable { authorize(broker, ticket, pid, authorized) },
                                "reload-handover",
                            )
                        receiver.setDaemon(true)
                        receiver.start()
                        try {
                            Files.deleteIfExists(ReloadCommand.HOST.resolve(ReloadTicket.CANCEL))
                            Files.deleteIfExists(ReloadCommand.HOST.resolve(ReloadTicket.RESULT))
                            write(ReloadTicket.REQUEST, ticket.properties())
                            // Verify again immediately before terminating the exact process from
                            // this request.
                            if (unlocked() != "OK" || !isSystemUi(pid)) {
                                Files.deleteIfExists(
                                    ReloadCommand.HOST.resolve(ReloadTicket.REQUEST)
                                )
                                return "STATE_CHANGED"
                            }
                            Process.sendSignal(pid, 15)
                            val deadline = SystemClock.uptimeMillis() + 10000
                            while (SystemClock.uptimeMillis() < deadline) {
                                if (
                                    !context
                                        .getSystemService<PowerManager?>(PowerManager::class.java)
                                        .isInteractive()
                                ) {
                                    authorized.set(false)
                                    write(ReloadTicket.CANCEL, ticket.properties())
                                    Files.deleteIfExists(
                                        ReloadCommand.HOST.resolve(ReloadTicket.REQUEST)
                                    )
                                    return "SCREEN_OFF"
                                }
                                val result =
                                    ReloadCommand.read(
                                        ReloadCommand.HOST.resolve(ReloadTicket.RESULT)
                                    )
                                if (
                                    ticket.nonce.equals(result.getProperty("nonce")) &&
                                        "resumed" == result.getProperty("phase")
                                ) {
                                    val next = result.getProperty("pid", "0").toInt()
                                    if (next != pid && isSystemUi(next) && unlocked() == "OK")
                                        return "RELOADED"
                                }
                                SystemClock.sleep(50)
                            }
                            write(ReloadTicket.CANCEL, ticket.properties())
                            Files.deleteIfExists(ReloadCommand.HOST.resolve(ReloadTicket.REQUEST))
                            return "UNLOCK_NEEDED"
                        } finally {
                            authorized.set(false)
                        }
                    }
                }
            }
    }

    private fun authorize(
        server: LocalServerSocket,
        ticket: ReloadTicket,
        oldPid: Int,
        authorized: AtomicBoolean,
    ) {
        while (authorized.get()) {
            try {
                server.accept().use { socket ->
                    socket.setSoTimeout(1000)
                    val peer = socket.getPeerCredentials()
                    if (
                        peer.getUid() != hostUid ||
                            peer.getPid() == oldPid ||
                            !isSystemUi(peer.getPid())
                    )
                        continue
                    val request =
                        String(
                            socket.getInputStream().readNBytes(37),
                            StandardCharsets.US_ASCII,
                        )
                    if (
                        (request != ticket.nonce + "\n") ||
                            !authorized.get() ||
                            !context
                                .getSystemService<PowerManager?>(PowerManager::class.java)
                                .isInteractive()
                    )
                        continue
                    socket.getOutputStream().write("ALLOW\n".toByteArray(StandardCharsets.US_ASCII))
                    return
                }
            } catch (stopped: IOException) {
                return
            }
        }
    }

    @Throws(Exception::class)
    private fun unlocked(): String? {
        val user =
            (ReloadCommand.invokeStatic(
                    Class.forName("android.app.ActivityManager"),
                    "getCurrentUser",
                ) as Number)
                .toInt()
        if (user != 0) return "UNSUPPORTED_USER"
        if (
            boot < 0 ||
                !context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked()
        )
            return "LOCKED"
        if (!context.getSystemService<PowerManager?>(PowerManager::class.java).isInteractive())
            return "SCREEN_OFF"
        val window = ReloadCommand.service("window", "android.view.IWindowManager\$Stub")
        val trust = ReloadCommand.service("trust", "android.app.trust.ITrustManager\$Stub")
        return if (
            java.lang.Boolean.TRUE == ReloadCommand.invoke(window, "isKeyguardLocked") ||
                java.lang.Boolean.TRUE == ReloadCommand.invoke(trust, "isDeviceLocked", 0, 0)
        )
            "LOCKED"
        else "OK"
    }

    private fun trustedCompanion(): Boolean {
        try {
            val info =
                context
                    .getPackageManager()
                    .getPackageInfo(
                        ReloadCommand.BAR,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
            if (info.getLongVersionCode() < 39 || info.signingInfo == null) return false
            for (certificate in info.signingInfo!!.getApkContentsSigners()) {
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                val hex = StringBuilder()
                for (part in digest) hex.append(
                    String.format(Locale.ROOT, "%02x", part.toInt() and 255)
                )
                if (ReloadCommand.BAR_SIGNER.contentEquals(hex)) return true
            }
        } catch (unavailable: Exception) {
            return false
        }
        return false
    }

    private fun matchesSupport(support: Properties, pid: Int): Boolean {
        return ReloadTicket.PROTOCOL.toString() == support.getProperty("protocol") &&
            boot.toString() == support.getProperty("boot") &&
            pid.toString() == support.getProperty("pid")
    }

    @Throws(Exception::class)
    private fun systemUiPid(): Int {
        val process = ProcessBuilder("/system/bin/pidof", "com.android.systemui").start()
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) return 0
            val text =
                String(
                        process.getInputStream().readNBytes(128),
                        StandardCharsets.US_ASCII,
                    )
                    .trim { it <= ' ' }
            if (!text.matches("[1-9][0-9]{0,8}".toRegex())) return 0
            val pid = text.toInt()
            return if (isSystemUi(pid)) pid else 0
        } finally {
            process.destroyForcibly()
        }
    }

    private fun isSystemUi(pid: Int): Boolean {
        if (pid <= 0) return false
        try {
            Files.newInputStream(Path.of("/proc", pid.toString(), "cmdline")).use { input ->
                val text = input.readNBytes(128)
                var end = 0
                while (end < text.size && text[end].toInt() != 0) end++
                if ("com.android.systemui" != String(text, 0, end, StandardCharsets.UTF_8))
                    return false
                return Files.readAllLines(Path.of("/proc", pid.toString(), "status"))
                    .stream()
                    .anyMatch { line: String ->
                        line.matches(
                            ("Uid:\\s+" +
                                    hostUid +
                                    "\\s+" +
                                    hostUid +
                                    "\\s+" +
                                    hostUid +
                                    "\\s+" +
                                    hostUid +
                                    "\\s*")
                                .toRegex()
                        )
                    }
            }
        } catch (missing: IOException) {
            return false
        }
    }

    @Throws(Exception::class)
    private fun write(name: String?, properties: Properties) {
        val temporary = ReloadCommand.HOST.resolve(name + ".root-tmp")
        Files.newOutputStream(temporary).use { output ->
            properties.store(output, "Galaxy Reload")
        }
        Os.chown(temporary.toString(), hostUid, hostUid)
        Os.chmod(temporary.toString(), 384)
        Files.move(
            temporary,
            ReloadCommand.HOST.resolve(name),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    companion object {
        private val HOST = Path.of("/data/user_de/0/com.android.systemui/files")
        private val CLI = Path.of("/data/local/tmp/galaxy-reload")
        private const val BAR = "dev.ryuya.galaxybar"
        private const val BAR_SIGNER =
            "aaecd6957a8e72d09462e04204b04b23c4f3bd3d9534fc62c4eb2ba29596265d"
        private val WRAPPER =
            """
            #!/system/bin/sh
            # Galaxy Reload CLI protocol 1
            app_apk=${'$'}(pm path dev.ryuya.galaxyreload | sed -n 's/^package://p' | head -n 1)
            [ -n "${'$'}app_apk" ] || { echo APP_NOT_INSTALLED; exit 2; }
            export CLASSPATH="${'$'}app_apk"
            exec /system/bin/app_process / dev.ryuya.galaxyreload.ReloadCommand "${'$'}@"

            """
                .trimIndent()

        @JvmStatic
        fun main(args: Array<String>) {
            var status: String?
            try {
                if (Process.myUid() != 0) status = "ROOT_REQUIRED"
                else {
                    if (Looper.myLooper() == null) Looper.prepareMainLooper()
                    val tool = ReloadCommand()
                    val thread =
                        ReloadCommand.invokeStatic(
                            Class.forName("android.app.ActivityThread"),
                            "systemMain",
                        )
                    tool.context =
                        ReloadCommand.invoke(requireNotNull(thread), "getSystemContext") as Context
                    tool.boot = ReloadCommand.readBootCount()
                    val host =
                        tool.context
                            .getPackageManager()
                            .getApplicationInfo("com.android.systemui", 0)
                    if (
                        (host.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            host.uid < 1000 ||
                            host.uid >= 100000
                    )
                        throw SecurityException("Unexpected host")
                    tool.hostUid = host.uid
                    val command =
                        if (args.size > 1) "invalid" else if (args.size == 0) "help" else args[0]
                    status =
                        when (command) {
                            "check" -> tool.check()
                            "reload" -> tool.reload()
                            "reload-locked" -> tool.reloadLocked()
                            "install-cli" -> ReloadCommand.installCli()
                            "help" ->
                                "USAGE: galaxy-reload check | reload | reload-locked | install-cli"
                            else -> "UNKNOWN_COMMAND"
                        }
                }
            } catch (failure: Exception) {
                Log.e("GalaxyReload", "Command failed", failure)
                status = "FAILED"
            }
            println(status)
            System.out.flush()
            System.exit(
                if (
                    java.util.Set.of<String?>(
                            "READY",
                            "SETUP",
                            "RELOADED",
                            "RELOADED_LOCKED",
                            "INSTALLED",
                        )
                        .contains(status) || status!!.startsWith("USAGE:")
                )
                    0
                else 2
            )
        }

        private fun firmwareSupported(): Boolean {
            return "m3q" == Build.DEVICE &&
                (Build.VERSION.SDK_INT == 36 && "BP4A.251205.006.S948QOPS1AZHL" == Build.DISPLAY ||
                    Build.VERSION.SDK_INT == 37 &&
                        "samsung".equals(Build.MANUFACTURER, ignoreCase = true) &&
                        "SM-S948Q" == Build.MODEL &&
                        "CP2A.260605.016.S948QOPU1BZI9" == Build.DISPLAY)
        }

        @Throws(Exception::class)
        private fun readBootCount(): Int {
            val command =
                ProcessBuilder("/system/bin/settings", "get", "global", "boot_count").start()
            try {
                if (!command.waitFor(3, TimeUnit.SECONDS) || command.exitValue() != 0) return -1
                val text =
                    String(
                            command.getInputStream().readNBytes(64),
                            StandardCharsets.US_ASCII,
                        )
                        .trim { it <= ' ' }
                return if (text.matches("[0-9]{1,9}".toRegex())) text.toInt() else -1
            } finally {
                command.destroyForcibly()
            }
        }

        @Throws(Exception::class)
        private fun read(path: Path?): Properties {
            val result = Properties()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return result
            if (Files.size(path) > 2048) throw IOException("Oversized reload record")
            Files.newInputStream(path).use { input ->
                result.load(input)
            }
            return result
        }

        @Throws(Exception::class)
        private fun installCli(): String? {
            if (
                Files.exists(
                    ReloadCommand.CLI,
                    LinkOption.NOFOLLOW_LINKS,
                )
            ) {
                if (
                    Files.isSymbolicLink(ReloadCommand.CLI) ||
                        Files.size(ReloadCommand.CLI) > 4096 ||
                        !String(
                                Files.readAllBytes(ReloadCommand.CLI),
                                StandardCharsets.UTF_8,
                            )
                            .contains("# Galaxy Reload CLI protocol 1")
                )
                    return "CLI_CONFLICT"
            }
            val temporary = ReloadCommand.CLI.resolveSibling(".galaxy-reload-" + UUID.randomUUID())
            Files.write(
                temporary,
                ReloadCommand.WRAPPER.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
            )
            Os.chmod(temporary.toString(), 493)
            Files.move(
                temporary,
                ReloadCommand.CLI,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return "INSTALLED"
        }

        @Throws(Exception::class)
        private fun service(name: String, stub: String?): Any {
            val binder =
                ReloadCommand.invokeStatic(
                    Class.forName("android.os.ServiceManager"),
                    "getService",
                    name,
                )
            if (binder == null) throw IOException("Service unavailable")
            return requireNotNull(
                ReloadCommand.invokeStatic(
                    Class.forName(stub),
                    "asInterface",
                    binder,
                )
            )
        }

        @Throws(Exception::class)
        private fun invokeStatic(
            type: Class<*>?,
            name: String?,
            vararg args: Any?,
        ): Any? {
            return ReloadCommand.method(type, name, args.size).invoke(null, *args)
        }

        @Throws(Exception::class)
        private fun invoke(
            target: Any,
            name: String?,
            vararg args: Any?,
        ): Any? {
            return ReloadCommand.method(target.javaClass, name, args.size).invoke(target, *args)
        }

        @Throws(NoSuchMethodException::class)
        private fun method(
            type: Class<*>?,
            name: String?,
            count: Int,
        ): Method {
            var current = type
            while (current != null) {
                for (method in current.getDeclaredMethods()) if (
                    method.getName() == name && method.getParameterCount() == count
                ) {
                    method.setAccessible(true)
                    return method
                }
                current = current.getSuperclass()
            }
            throw NoSuchMethodException(name)
        }
    }
}
