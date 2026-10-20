package dev.ryuya.galaxyreload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Fixed commands only. Both process lifetime and captured output are bounded. */
final class RootShell {
  static final class Denied extends IOException {}

  static final class CommandFailed extends IOException {}

  static final class TimedOut extends IOException {}

  static void requireRoot() throws Exception {
    try {
      if (!run("exec /system/bin/id -u").trim().equals("0")) throw new Denied();
    } catch (CommandFailed denied) {
      throw new Denied();
    }
  }

  static String run(String command) throws Exception {
    Process process;
    try {
      process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
    } catch (IOException missing) {
      throw new Denied();
    }
    ExecutorService reader = Executors.newSingleThreadExecutor();
    Future<String> output =
        reader.submit(
            () -> {
              try (var input = process.getInputStream();
                  var result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                  if (result.size() + count > 16384)
                    throw new IOException("Command output too large");
                  result.write(buffer, 0, count);
                }
                return result.toString(StandardCharsets.UTF_8);
              }
            });
    try {
      if (!process.waitFor(20, TimeUnit.SECONDS)) throw new TimedOut();
      String text = output.get(2, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new CommandFailed();
      return text;
    } finally {
      process.destroyForcibly();
      reader.shutdownNow();
    }
  }
}
