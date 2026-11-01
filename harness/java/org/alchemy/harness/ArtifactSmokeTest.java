package org.alchemy.harness;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the <em>shipped artifact</em> the way a user does - {@code ./Alchemy} - and then
 * reads what the product itself reports.
 *
 * <p>Why a second launch test when {@code :smokeTest} already boots the app: smokeTest
 * runs Alchemy's classes off a classpath that Gradle assembles, so it keeps passing even
 * when the packaged jar cannot start on its own. That is exactly the hole that let
 * {@code java -jar alchemy.jar} die with {@code NoClassDefFoundError} while CI was green.
 * Nothing in this class has a privileged classpath - it does not even load an Alchemy
 * class - so the only thing it can accidentally forgive is a packaging mistake.
 *
 * <p>It asserts what the framework reports rather than what a human would notice:
 *
 * <ol>
 *   <li>the launcher starts a JVM from inside the unpacked package;
 *   <li>the plugin framework reaches "active/total: N of N", i.e. every module archive
 *       in {@code modules/} was found, unpacked and started;
 *   <li>the merged stdout/stderr of the real product contains no allowed-outside
 *       exception trace (shared allowlist with {@link SmokeTest}, so a trace can never
 *       be forgiven in one harness and fail in the other);
 *   <li>the process is still alive when the count completes - an app that exits during
 *       boot has not started, however polite its logs were.
 * </ol>
 *
 * <p>No window geometry, pixels or screen capture are involved; see the note in
 * {@link SmokeTest} about grabbing the screen.
 *
 * <p>Arguments: {@code <packageDir> <timeoutSeconds> <expectedPlugins>}.
 * Result: {@code artifact-result.txt} (path in system property
 * {@code alchemy.artifact.result}) and exit code 0/2.
 */
public final class ArtifactSmokeTest {

    /** {@code (active/total: 28 of 28)} - JPF's own boot tally, logged per plugin. */
    private static final Pattern PLUGIN_TALLY =
            Pattern.compile("\\(active/total:\\s*(\\d+)\\s+of\\s+(\\d+)\\)");

    /** The shipped app is X11-independent, but a headless JVM cannot open a window. */
    private static final long POLL_MILLIS = 250L;

    private static final List<String> details = new ArrayList<String>();
    private static final StringBuilder console = new StringBuilder();
    private static final int CAPTURE_LIMIT = 400_000;

    private ArtifactSmokeTest() {
    }

    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0] : ".").getAbsoluteFile();
        long timeoutMillis = (args.length > 1 ? Long.parseLong(args[1]) : 180L) * 1000L;
        int expectedPlugins = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        List<String> failures = new ArrayList<String>();
        details.add("package_dir=" + dir);
        Process process = null;

        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            File script = new File(dir, windows ? "Alchemy.bat" : "Alchemy");
            if (!script.isFile()) {
                fail(failures, "launcher " + script + " is missing from the package");
                throw new IOException("no launcher");
            }

            List<String> command = windows
                    ? List.of("cmd.exe", "/c", script.getName())
                    : List.of(script.getAbsolutePath());
            details.add("command=" + String.join(" ", command));

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true);
            process = builder.start();
            pump(process);

            long deadline = System.currentTimeMillis() + timeoutMillis;
            boolean alive = true;
            int[] tally = null;
            while (System.currentTimeMillis() < deadline) {
                alive = process.isAlive();
                tally = lastTally();
                if (tally != null && tally[0] == tally[1]) {
                    break;
                }
                if (!alive) {
                    break;
                }
                Thread.sleep(POLL_MILLIS);
            }

            details.add("boot_tally=" + (tally == null ? "never logged" : tally[0] + " of " + tally[1]));
            if (tally == null) {
                fail(failures, "the plugin framework never logged a boot tally; the app most likely "
                        + "exited before it got that far (see the console tail below)");
            } else if (tally[0] != tally[1]) {
                fail(failures, "only " + tally[0] + " of " + tally[1] + " modules started within "
                        + (timeoutMillis / 1000L) + "s");
            } else if (expectedPlugins > 0 && tally[1] != expectedPlugins) {
                fail(failures, "the app started " + tally[1] + " modules, the build produced "
                        + expectedPlugins + " module archives");
            }
            if (!alive) {
                fail(failures, "the application process exited during start-up");
            }

            List<String> traces = new ArrayList<String>();
            synchronized (console) {
                SmokeTest.LogTee.scan(console.toString(), SmokeTest.ALLOWED_EXCEPTIONS, traces);
            }
            if (!traces.isEmpty()) {
                fail(failures, "exception traces while running the packaged artifact: " + traces);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(failures, "interrupted while waiting for the packaged app to start");
        } catch (IOException problem) {
            if (failures.isEmpty()) {
                fail(failures, "could not launch the packaged app: " + problem);
            }
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }

        String tail = tail();
        if (!tail.isEmpty()) {
            details.add("console_tail=" + tail);
        }
        report(failures);
    }

    /** Stream the child's merged output into a buffer; never let its pipe fill up. */
    private static void pump(final Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (console) {
                        console.append(line).append('\n');
                        if (console.length() > CAPTURE_LIMIT) {
                            console.delete(0, console.length() - CAPTURE_LIMIT);
                        }
                    }
                }
            } catch (IOException expectedWhenKilled) {
                // The stream closes when the child is destroyed - the normal ending.
            }
        }, "artifact-output");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * The most recent {@code (active/total: n of m)} the product has logged.
     *
     * <p>Returns a snapshot rather than a Matcher: a matcher that has been driven to the
     * end of the input is past its last match, and reading groups from it afterwards
     * throws.
     */
    private static int[] lastTally() {
        synchronized (console) {
            int[] last = null;
            Matcher matcher = PLUGIN_TALLY.matcher(console.toString());
            while (matcher.find()) {
                last = new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
            }
            return last;
        }
    }

    /** Last few lines of console output, flattened - the difference between "it failed" and a diagnosis. */
    private static String tail() {
        String text;
        synchronized (console) {
            text = console.toString();
        }
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - 5);
        StringBuilder tail = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            tail.append(lines[i].trim()).append(" | ");
        }
        return tail.length() > 900 ? tail.substring(tail.length() - 900) : tail.toString();
    }

    private static void fail(List<String> failures, String problem) {
        failures.add(problem);
    }

    private static void report(List<String> failures) {
        StringBuilder body = new StringBuilder();
        body.append("result=").append(failures.isEmpty() ? "PASS" : "FAIL").append('\n');
        for (String detail : details) {
            body.append(detail).append('\n');
        }
        for (String problem : failures) {
            body.append("failure=").append(problem).append('\n');
        }
        System.out.println("----- packaged artifact check -----");
        System.out.print(body);
        System.out.println("-----------------------------------");
        try {
            Path result = Paths.get(System.getProperty("alchemy.artifact.result", "artifact-result.txt"));
            if (result.getParent() != null) {
                Files.createDirectories(result.getParent());
            }
            Files.write(result, body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException problem) {
            System.out.println("failure=cannot write result file: " + problem);
        }
        System.out.flush();
        System.exit(failures.isEmpty() ? 0 : 2);
    }
}
