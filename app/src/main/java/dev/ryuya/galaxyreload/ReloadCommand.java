package dev.ryuya.galaxyreload;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root-only command entry. All targets and shell commands are fixed. */
public final class ReloadCommand {
  private static final Path HOST = Path.of("/data/user_de/0/com.android.systemui/files");
  private static final Path CLI = Path.of("/data/local/tmp/galaxy-reload");
  private static final String BAR = "dev.ryuya.galaxybar";
  private static final String BAR_SIGNER =
      "aaecd6957a8e72d09462e04204b04b23c4f3bd3d9534fc62c4eb2ba29596265d";
  private static final String WRAPPER =
      """
      #!/system/bin/sh
      # Galaxy Reload CLI protocol 1
      app_apk=$(pm path dev.ryuya.galaxyreload | sed -n 's/^package://p' | head -n 1)
      [ -n "$app_apk" ] || { echo APP_NOT_INSTALLED; exit 2; }
      export CLASSPATH="$app_apk"
      exec /system/bin/app_process / dev.ryuya.galaxyreload.ReloadCommand "$@"
      """;
  private Context context;
  private int boot;

  public static void main(String[] args) {
    String status;
    try {
      if (android.os.Process.myUid() != 0) status = "ROOT_REQUIRED";
      else {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        ReloadCommand tool = new ReloadCommand();
        Object thread = invokeStatic(Class.forName("android.app.ActivityThread"), "systemMain");
        tool.context = (Context) invoke(thread, "getSystemContext");
        tool.boot = readBootCount();
        String command = args.length > 1 ? "invalid" : args.length == 0 ? "help" : args[0];
        status =
            switch (command) {
              case "check" -> tool.check();
              case "reload" -> tool.reload();
              case "install-cli" -> installCli();
              case "help" -> "USAGE: galaxy-reload check | reload | install-cli";
              default -> "UNKNOWN_COMMAND";
            };
      }
    } catch (Exception failure) {
      android.util.Log.e("GalaxyReload", "Command failed", failure);
      status = "FAILED";
    }
    System.out.println(status);
    System.out.flush();
    System.exit(
        Set.of("READY", "SETUP", "RELOADED", "INSTALLED").contains(status)
                || status.startsWith("USAGE:")
            ? 0
            : 2);
  }

  private String check() throws Exception {
    if (!firmwareSupported()) return "UNSUPPORTED";
    if (!trustedCompanion()) return "MODULE_REQUIRED";
    String unlocked = unlocked();
    if (!unlocked.equals("OK")) return unlocked;
    int pid = systemUiPid();
    if (pid <= 0) return "NO_SYSTEMUI";
    Properties support = read(HOST.resolve(ReloadTicket.SUPPORT));
    return matchesSupport(support, pid) ? "READY" : "SETUP";
  }

  private String reload() throws Exception {
    String readiness = check();
    if (!readiness.equals("READY") && !readiness.equals("SETUP")) return readiness;
    // A single host-private file lock serializes GUI and CLI invocations.
    try (FileChannel channel =
            FileChannel.open(
                HOST.resolve("galaxy-reload-operation.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null) return "BUSY";
      int pid = systemUiPid();
      if (pid <= 0 || !isSystemUi(pid)) return "NO_SYSTEMUI";
      String preflight = unlocked();
      if (!preflight.equals("OK")) return preflight;
      ReloadTicket ticket =
          new ReloadTicket(boot, 0, SystemClock.uptimeMillis(), UUID.randomUUID().toString());
      AtomicBoolean authorized = new AtomicBoolean(true);
      try (LocalServerSocket broker = new LocalServerSocket("galaxy-reload-" + ticket.nonce())) {
        Thread receiver =
            new Thread(() -> authorize(broker, ticket, pid, authorized), "reload-handover");
        receiver.setDaemon(true);
        receiver.start();
        try {
          Files.deleteIfExists(HOST.resolve(ReloadTicket.CANCEL));
          Files.deleteIfExists(HOST.resolve(ReloadTicket.RESULT));
          write(ReloadTicket.REQUEST, ticket.properties());
          // Verify again immediately before terminating the exact process from this request.
          if (!unlocked().equals("OK") || !isSystemUi(pid)) {
            Files.deleteIfExists(HOST.resolve(ReloadTicket.REQUEST));
            return "STATE_CHANGED";
          }
          android.os.Process.sendSignal(pid, 15);
          long deadline = SystemClock.uptimeMillis() + 10_000;
          while (SystemClock.uptimeMillis() < deadline) {
            if (!context.getSystemService(PowerManager.class).isInteractive()) {
              authorized.set(false);
              write(ReloadTicket.CANCEL, ticket.properties());
              Files.deleteIfExists(HOST.resolve(ReloadTicket.REQUEST));
              return "SCREEN_OFF";
            }
            Properties result = read(HOST.resolve(ReloadTicket.RESULT));
            if (ticket.nonce().equals(result.getProperty("nonce"))
                && "resumed".equals(result.getProperty("phase"))) {
              int next = Integer.parseInt(result.getProperty("pid", "0"));
              if (next != pid && isSystemUi(next) && unlocked().equals("OK")) return "RELOADED";
            }
            SystemClock.sleep(50);
          }
          write(ReloadTicket.CANCEL, ticket.properties());
          Files.deleteIfExists(HOST.resolve(ReloadTicket.REQUEST));
          return "UNLOCK_NEEDED";
        } finally {
          authorized.set(false);
        }
      }
    }
  }

  private void authorize(
      LocalServerSocket server, ReloadTicket ticket, int oldPid, AtomicBoolean authorized) {
    while (authorized.get()) {
      try (LocalSocket socket = server.accept()) {
        socket.setSoTimeout(1000);
        var peer = socket.getPeerCredentials();
        if (peer.getUid() != 1000 || peer.getPid() == oldPid || !isSystemUi(peer.getPid()))
          continue;
        String request =
            new String(socket.getInputStream().readNBytes(37), StandardCharsets.US_ASCII);
        if (!request.equals(ticket.nonce() + "\n")
            || !authorized.get()
            || !context.getSystemService(PowerManager.class).isInteractive()) continue;
        socket.getOutputStream().write("ALLOW\n".getBytes(StandardCharsets.US_ASCII));
        return;
      } catch (IOException stopped) {
        return;
      }
    }
  }

  private String unlocked() throws Exception {
    int user =
        ((Number) invokeStatic(Class.forName("android.app.ActivityManager"), "getCurrentUser"))
            .intValue();
    if (user != 0) return "UNSUPPORTED_USER";
    if (boot < 0 || !context.getSystemService(UserManager.class).isUserUnlocked()) return "LOCKED";
    if (!context.getSystemService(PowerManager.class).isInteractive()) return "SCREEN_OFF";
    Object window = service("window", "android.view.IWindowManager$Stub");
    Object trust = service("trust", "android.app.trust.ITrustManager$Stub");
    return Boolean.TRUE.equals(invoke(window, "isKeyguardLocked"))
            || Boolean.TRUE.equals(invoke(trust, "isDeviceLocked", 0, 0))
        ? "LOCKED"
        : "OK";
  }

  private boolean trustedCompanion() {
    try {
      var info =
          context.getPackageManager().getPackageInfo(BAR, PackageManager.GET_SIGNING_CERTIFICATES);
      if (info.getLongVersionCode() < 39 || info.signingInfo == null) return false;
      for (var certificate : info.signingInfo.getApkContentsSigners()) {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte part : digest) hex.append(String.format(Locale.ROOT, "%02x", part & 255));
        if (BAR_SIGNER.contentEquals(hex)) return true;
      }
    } catch (Exception unavailable) {
      return false;
    }
    return false;
  }

  private static boolean firmwareSupported() {
    return "m3q".equals(Build.DEVICE)
        && (Build.VERSION.SDK_INT == 36 && "BP4A.251205.006.S948QOPS1AZHL".equals(Build.DISPLAY)
            || Build.VERSION.SDK_INT == 37
                && "samsung".equalsIgnoreCase(Build.MANUFACTURER)
                && "SM-S948Q".equals(Build.MODEL)
                && "CP2A.260605.016.S948QOPU1BZI9".equals(Build.DISPLAY));
  }

  private boolean matchesSupport(Properties support, int pid) {
    return Integer.toString(ReloadTicket.PROTOCOL).equals(support.getProperty("protocol"))
        && Integer.toString(boot).equals(support.getProperty("boot"))
        && Integer.toString(pid).equals(support.getProperty("pid"));
  }

  private static int readBootCount() throws Exception {
    java.lang.Process command =
        new ProcessBuilder("/system/bin/settings", "get", "global", "boot_count").start();
    try {
      if (!command.waitFor(3, TimeUnit.SECONDS) || command.exitValue() != 0) return -1;
      String text =
          new String(command.getInputStream().readNBytes(64), StandardCharsets.US_ASCII).trim();
      return text.matches("[0-9]{1,9}") ? Integer.parseInt(text) : -1;
    } finally {
      command.destroyForcibly();
    }
  }

  private static int systemUiPid() throws Exception {
    java.lang.Process process =
        new ProcessBuilder("/system/bin/pidof", "com.android.systemui").start();
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) return 0;
      String text =
          new String(process.getInputStream().readNBytes(128), StandardCharsets.US_ASCII).trim();
      if (!text.matches("[1-9][0-9]{0,8}")) return 0;
      int pid = Integer.parseInt(text);
      return isSystemUi(pid) ? pid : 0;
    } finally {
      process.destroyForcibly();
    }
  }

  private static boolean isSystemUi(int pid) {
    if (pid <= 0) return false;
    try (var input = Files.newInputStream(Path.of("/proc", Integer.toString(pid), "cmdline"))) {
      byte[] text = input.readNBytes(128);
      int end = 0;
      while (end < text.length && text[end] != 0) end++;
      if (!"com.android.systemui".equals(new String(text, 0, end, StandardCharsets.UTF_8)))
        return false;
      return Files.readAllLines(Path.of("/proc", Integer.toString(pid), "status")).stream()
          .anyMatch(line -> line.matches("Uid:\\s+1000\\s+1000\\s+1000\\s+1000\\s*"));
    } catch (IOException missing) {
      return false;
    }
  }

  private static Properties read(Path path) throws Exception {
    Properties result = new Properties();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return result;
    if (Files.size(path) > 2048) throw new IOException("Oversized reload record");
    try (var input = Files.newInputStream(path)) {
      result.load(input);
    }
    return result;
  }

  private static void write(String name, Properties properties) throws Exception {
    Path temporary = HOST.resolve(name + ".root-tmp");
    try (var output = Files.newOutputStream(temporary)) {
      properties.store(output, "Galaxy Reload");
    }
    Os.chown(temporary.toString(), 1000, 1000);
    Os.chmod(temporary.toString(), 0600);
    Files.move(
        temporary,
        HOST.resolve(name),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private static String installCli() throws Exception {
    if (Files.exists(CLI, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(CLI)
          || Files.size(CLI) > 4096
          || !new String(Files.readAllBytes(CLI), StandardCharsets.UTF_8)
              .contains("# Galaxy Reload CLI protocol 1")) return "CLI_CONFLICT";
    }
    Path temporary = CLI.resolveSibling(".galaxy-reload-" + UUID.randomUUID());
    Files.write(temporary, WRAPPER.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    Os.chmod(temporary.toString(), 0755);
    Files.move(temporary, CLI, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    return "INSTALLED";
  }

  private static Object service(String name, String stub) throws Exception {
    Object binder = invokeStatic(Class.forName("android.os.ServiceManager"), "getService", name);
    if (binder == null) throw new IOException("Service unavailable");
    return invokeStatic(Class.forName(stub), "asInterface", binder);
  }

  private static Object invokeStatic(Class<?> type, String name, Object... args) throws Exception {
    return method(type, name, args.length).invoke(null, args);
  }

  private static Object invoke(Object target, String name, Object... args) throws Exception {
    return method(target.getClass(), name, args.length).invoke(target, args);
  }

  private static Method method(Class<?> type, String name, int count) throws NoSuchMethodException {
    for (Class<?> current = type; current != null; current = current.getSuperclass())
      for (Method method : current.getDeclaredMethods())
        if (method.getName().equals(name) && method.getParameterCount() == count) {
          method.setAccessible(true);
          return method;
        }
    throw new NoSuchMethodException(name);
  }
}
