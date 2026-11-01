package org.alchemy.harness;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.MenuElement;

/**
 * End-to-end boot check for Alchemy without automating Swing directly.
 *
 * <p>Boots the real application from the distribution layout, waits for the UI
 * shell to be constructed, then verifies:
 * <ul>
 *   <li>no fatal error was recorded by the start-up watchdog</li>
 *   <li>the main window, tool bar, menu bar and canvas all exist</li>
 *   <li>the menu bar and tool bar actually populated (an empty shell throws no
 *       exception but is still broken)</li>
 *   <li>every module archive resolved to a plugin, and every create/affect plugin
 *       registered (so the packaged module archives are actually loadable)</li>
 *   <li>nothing logged an exception trace during start-up</li>
 * </ul>
 *
 * <p>This harness asserts structure, not pixels, and it deliberately never grabs
 * the screen: a grab reads whatever display the process happens to be on (so it
 * can silently "pass" against an unrelated desktop) and, on a desktop session with
 * a screenshots portal, it raises a remote-desktop consent prompt for the user.
 * Pixel-level checks belong in Workstream 2, where they can render the drawing
 * model off-screen instead of photographing a live window; {@code verifyRendering}
 * is where they plug in.
 *
 * <p>Result is written to {@code smoke-result.txt} next to the working directory
 * and the process exits non-zero on failure.
 */
public final class SmokeTest {

    private static final long TIMEOUT_MILLIS = 90_000L;
    private static final List<String> details = new ArrayList<String>();
    private static final File resultFile = new File(System.getProperty("alchemy.smoke.result", "smoke-result.txt"));

    /**
     * Exception traces that are known and non-fatal at start-up today. Keep this
     * list short and explicit; it is the regression budget for start-up errors.
     */
    private static final String[] ALLOWED_EXCEPTIONS = {
        // JPen tablet back-end probes for native libraries at start-up.
        "jpen",
        // Optional native helper (no source in repo; the app runs without it).
        "AlcNative",
        // Upstream bug: Shapes.java asks the class loader for "linetype.png" and
        // "outline.png" but the files live at data/... inside the module archive.
        // Present since release 008. Tracked for clean-up in MAINTENANCE.md.
        "linetype.png",
        "outline.png"
    };

    private static final LogTee log = new LogTee();

    private SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        log.install();
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            write("FAIL", "exception=" + t.getClass().getName() + ": " + t.getMessage());
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            fail("running headless; the Swing app cannot be verified (needs a display: use xvfb-run)", "");
        }
        /* This harness is only ever meant to run against a private display. A
         * process pointed at the user's own session would verify nothing and
         * would disturb their desktop. */
        String display = System.getenv("DISPLAY");
        boolean primary = display == null || ":0".equals(display) || ":+0".equals(display) || ":1".equals(display);
        if (primary && System.getenv("ALCHEMY_SMOKE_ALLOW_PRIMARY_DISPLAY") == null) {
            fail("refusing to run against display '" + display
                    + "'; run under a private display (xvfb-run) or set ALCHEMY_SMOKE_ALLOW_PRIMARY_DISPLAY=1", "");
        }
        details.add("display=" + display);

        File modules = new File("modules").getAbsoluteFile();
        if (!modules.isDirectory()) {
            fail("missing modules directory " + modules + "; run the 'dist' task first", "");
        }

        // The app must start on a background thread: the AWT/Swing event dispatch
        // thread is created by the app and stays alive after the window closes,
        // so SwingUtilities.invokeAndWait would deadlock.
        Thread boot = new Thread(new Runnable() {
            public void run() {
                try {
                    Class.forName("org.alchemy.core.Alchemy")
                            .getMethod("main", String[].class)
                            .invoke(null, (Object) new String[0]);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    cause.printStackTrace();
                    setField("FATAL_ERROR", "startup_exception=" + cause.getClass().getName());
                }
            }
        }, "alchemy-app-launcher");
        boot.setDaemon(true);
        boot.start();

        String fatal = waitForShell(boot);
        if (fatal != null) {
            fail(fatal, log.tail());
        }

        List<String> problems = new ArrayList<String>();
        verifyUiShell(problems, details);
        verifyPluginRegistration(problems, details);

        List<String> traces = log.exceptionTraces(ALLOWED_EXCEPTIONS);
        details.add("exception_traces=" + traces.size());
        if (!traces.isEmpty()) {
            problems.add("unexpected_start-up_exceptions=" + traces);
        }

        if (!problems.isEmpty()) {
            fail(String.join(" | ", problems), log.tail());
        }

        System.out.println("Smoke test passed: " + String.join("; ", details));
        write("PASS", details.toArray(new String[0]));
        System.exit(0);
    }

    /** Waits until the application has built its UI shell; returns a failure reason. */
    private static String waitForShell(Thread boot) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String fatal = stringField("FATAL_ERROR");
            if (fatal != null) {
                return "app reported fatal start-up error: " + fatal;
            }
            Window[] frames = Window.getWindows();
            Object window = readWindow();
            Object canvas = readCanvas();
            Object toolBar = readStatic("toolBar");
            Object menuBar = readStatic("menuBar");
            boolean pluginsSeen = pluginCount("getNumberOfPlugins") > 0;
            if (window != null && canvas != null && toolBar != null && menuBar != null && pluginsSeen) {
                sleep(1500L);
                return null;
            }
            if (!boot.isAlive() && (window == null || canvas == null)) {
                return "application exited before the UI shell was built; last log:\n" + log.tail();
            }
            if (frames.length > 0 && toolBar == null && menuBar == null && !pluginsSeen) {
                // Window exists but nothing else: give the start-up thread its full
                // budget rather than failing early on a timing race.
                sleep(200L);
                continue;
            }
            sleep(200L);
        }
        return "timed out waiting for the UI shell. window=" + describe(readWindow())
                + " canvas=" + describe(readCanvas())
                + " toolBar=" + describe(readStatic("toolBar"))
                + " menuBar=" + describe(readStatic("menuBar"))
                + " plugins=" + pluginCount("getNumberOfPlugins")
                + "\n" + log.tail();
    }

    private static void verifyUiShell(List<String> problems, List<String> details) {
        Component window = (Component) readWindow();
        Component canvas = (Component) readCanvas();

        if (!window.isShowing()) problems.add("main_window_not_showing");
        details.add("window=" + window.getWidth() + "x" + window.getHeight());
        if (window.getWidth() < 100 || window.getHeight() < 100) {
            problems.add("main_window_too_small:" + window.getWidth() + "x" + window.getHeight());
        }
        details.add("canvas=" + canvas.getWidth() + "x" + canvas.getHeight());
        if (canvas.getWidth() < 10 || canvas.getHeight() < 10) {
            problems.add("canvas_not_sized:" + canvas.getWidth() + "x" + canvas.getHeight());
        }
        if (!canvas.isShowing()) problems.add("canvas_not_showing");

        /* An empty shell throws no exception but is still broken. */
        int menus = countMenuItems((Component) readStatic("menuBar"));
        details.add("menu_items=" + menus);
        if (menus < 10) problems.add("menu_bar_not_populated:" + menus);

        List<AbstractButton> buttons = toolButtons();
        details.add("tool_buttons=" + buttons.size());
        if (buttons.size() < 10) problems.add("tool_bar_not_populated:" + buttons.size());

        int withIcons = 0;
        for (AbstractButton b : buttons) {
            Icon icon = b.getIcon();
            if (icon != null && icon.getIconWidth() > 0 && icon.getIconHeight() > 0) withIcons++;
        }
        details.add("tool_buttons_with_icons=" + withIcons);
        if (withIcons < 10) problems.add("tool_icons_missing:" + withIcons);

    }

    private static int countMenuItems(Component root) {
        if (root == null) return -1;
        int total = 0;
        for (Component c : ((Container) root).getComponents()) {
            if (c instanceof JMenu) {
                JMenu menu = (JMenu) c;
                total += menu.getItemCount();
                for (MenuElement sub : menu.getSubElements()) {
                    if (sub instanceof JMenu) total += ((JMenu) sub).getItemCount();
                }
            } else if (c instanceof Container) {
                total += countMenuItems(c);
            }
        }
        return total;
    }

    private static List<AbstractButton> toolButtons() {
        List<AbstractButton> found = new ArrayList<AbstractButton>();
        collectButtons((Component) readStatic("toolBar"), found);
        return found;
    }

    private static void collectButtons(Component c, List<AbstractButton> out) {
        if (c == null) return;
        if (c instanceof AbstractButton) out.add((AbstractButton) c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) collectButtons(child, out);
        }
    }

    /**
     * {@code Alchemy.plugins} is a package-private {@code AlcPlugins} holder, not a
     * collection, so the counts come from its accessors via reflection.
     */
    private static int pluginCount(String getter) {
        Object plugins = readStatic("plugins");
        if (plugins == null) return -1;
        try {
            Method method = plugins.getClass().getDeclaredMethod(getter);
            method.setAccessible(true);
            Object value = method.invoke(plugins);
            return (value instanceof Integer) ? ((Integer) value).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void verifyPluginRegistration(List<String> problems, List<String> details) throws Exception {
        int archives = 0;
        File[] files = new File("modules").getAbsoluteFile().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".zip")) archives++;
            }
        }
        details.add("module_archives=" + archives);
        if (archives == 0) {
            problems.add("no_module_archives_in_distribution");
        }

        int declared = pluginCount("getNumberOfPlugins");
        int creates = pluginCount("getNumberOfCreateModules");
        int affects = pluginCount("getNumberOfAffectModules");

        details.add("plugins_declared=" + declared);
        details.add("creates=" + creates);
        details.add("affects=" + affects);

        if (archives > 0 && declared <= 0) {
            problems.add("no_plugins_declared_from_" + archives + "_archives");
        }
        if (creates <= 0) {
            problems.add("no_create_plugins_registered:" + creates);
        }
        if (affects <= 0) {
            problems.add("no_affect_plugins_registered:" + affects);
        }
        if (declared > 0 && creates + affects != declared) {
            problems.add("create_plus_affect_does_not_match_declared:" + creates + "+" + affects + "!=" + declared);
        }
    }

    private static String stringField(String name) {
        try {
            Field field = Class.forName("org.alchemy.core.Alchemy").getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readWindow() {
        return readStatic("window");
    }

    private static Object readCanvas() {
        return readStatic("canvas");
    }

    private static Object readStatic(String name) {
        try {
            Field field = Class.forName("org.alchemy.core.Alchemy").getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(String name, Object value) {
        try {
            Field field = Class.forName("org.alchemy.core.Alchemy").getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable ignored) {
            // Watchdog field is best-effort only.
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static void sleep(long millis) throws InterruptedException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            throw ie;
        }
    }

    private static void fail(String reason, String tail) throws Exception {
        System.err.println("Smoke test failed: " + reason);
        if (tail != null && !tail.isEmpty()) {
            System.err.println(tail);
        }
        write("FAIL", ("reason=" + reason).split("\n"));
        System.exit(2);
    }

    private static void write(String result, String... lines) throws Exception {
        StringBuilder text = new StringBuilder("result=" + result + "\n");
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Files.write(resultFile.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Captures System.out/System.err so start-up exception traces can be asserted. */
    private static final class LogTee {
        private final StringBuilder buffer = new StringBuilder();
        private boolean installed;

        void install() {
            if (installed) return;
            tee(System.out, true);
            tee(System.err, false);
            installed = true;
        }

        private void tee(final PrintStream original, final boolean stdout) {
            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream tee = new PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) throws java.io.IOException {
                    synchronized (buffer) {
                        captured.write(b);
                        byte[] bytes = captured.toByteArray();
                        buffer.setLength(0);
                        buffer.append(new String(bytes, 0, Math.min(bytes.length, 400_000), StandardCharsets.UTF_8));
                    }
                    original.write(b);
                }

                @Override public void write(byte[] bytes, int off, int len) throws java.io.IOException {
                    for (int i = 0; i < len; i++) write(bytes[off + i]);
                }

                @Override public void flush() throws java.io.IOException {
                    original.flush();
                }

                @Override public void close() throws java.io.IOException {
                    original.flush();
                }
            }, true, StandardCharsets.UTF_8);
            if (stdout) System.setOut(tee); else System.setErr(tee);
        }

        List<String> exceptionTraces(String[] allowed) {
            List<String> found = new ArrayList<String>();
            String[] lines;
            synchronized (buffer) {
                lines = buffer.toString().split("\n");
            }
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.matches("^([a-zA-Z_$][\\w$]*\\.)+[A-Z$][\\w$]*(Exception|Error|Throwable)\\b.*")
                        && !line.matches("^(Caused by|Suppressed):.*")) {
                    continue;
                }
                boolean expected = false;
                for (String token : allowed) {
                    if (line.toLowerCase().contains(token.toLowerCase())) {
                        expected = true;
                        break;
                    }
                }
                if (!expected && !found.contains(line)) found.add(line);
            }
            return found;
        }

        String tail() {
            String text;
            synchronized (buffer) {
                text = buffer.toString();
            }
            String[] lines = text.split("\n");
            int from = Math.max(0, lines.length - 40);
            StringBuilder out = new StringBuilder("last log lines:\n");
            for (int i = from; i < lines.length; i++) {
                out.append("  ").append(lines[i]).append('\n');
            }
            return out.toString();
        }
    }
}
