package org.alchemy.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the smoke harness itself.
 *
 * The smoke test is what makes CI mean something, so its log parsing needs the same
 * protection as the assertions it feeds. Two defects motivated this file and both are
 * pinned below: stdout and stderr tees shared one capture buffer, so whichever stream
 * printed last erased the other and traces on stderr were invisible; and a trace header
 * was only recognised when the throwable type was package-qualified, which excluded
 * java.lang.Exception itself.
 *
 * Deliberately dependency-free (no JUnit) so it runs offline in a plain JavaExec, and it
 * neither loads Alchemy nor touches AWT, so it needs no display. Run with
 * ./gradlew harnessTest, which check depends on.
 */
public final class HarnessTest {

    private static int failures;
    private static int checks;

    private HarnessTest() {
    }

    public static void main(String[] args) {
        stderrTraceSurvivesStdoutTraffic();
        bareExceptionNameIsATraceHeader();
        proseStartingWithErrorIsNotATraceHeader();
        suffixedTypeNeedsNoFrame();
        allowedSubstringsAreIgnored();
        reportedEntryCarriesTheFirstFrame();
        causedByIsCollected();

        System.out.println(failures == 0
                ? "Harness test passed: " + checks + " checks"
                : "Harness test FAILED: " + failures + " of " + checks + " checks");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The regression that made the smoke test report zero traces for a run that threw. */
    private static void stderrTraceSurvivesStdoutTraffic() {
        SmokeTest.LogTee tee = new SmokeTest.LogTee();
        tee.install();
        System.err.println("java.io.IOException: ON STDERR");
        new Exception("ON STDERR TOO").printStackTrace();
        System.out.println("application log line on stdout, printed after both");

        check("stderr trace survives later stdout output",
                tee.exceptionTraces(new String[0]).size() == 2,
                "traces=" + tee.exceptionTraces(new String[0]));
        check("stdout sink kept its own text", tee.stdoutText().contains("application log line"),
                "stdout=" + tee.stdoutText());
        check("stderr sink kept its own text", tee.stderrText().contains("ON STDERR TOO"),
                "stderr=" + tee.stderrText());
    }

    private static void bareExceptionNameIsATraceHeader() {
        List<String> traces = traces(
                "java.lang.Exception: bare throwable type",
                "\tat org.alchemy.core.AlcCanvas.mousePressed(AlcCanvas.java:946)");
        check("java.lang.Exception counts as a trace header", traces.size() == 1, "traces=" + traces);
    }

    private static void proseStartingWithErrorIsNotATraceHeader() {
        List<String> traces = traces(
                "Error: no pen device found",
                "continuing with mouse input only");
        check("log prose that opens with \"Error:\" is not a trace", traces.isEmpty(),
                "traces=" + traces);
    }

    /** macOS reports NoSuchMethodError with a message and no frames; that must still fail. */
    private static void suffixedTypeNeedsNoFrame() {
        List<String> traces = traces(
                "java.lang.NoSuchMethodError: 'java.awt.peer.ComponentPeer java.awt.Container.getPeer()'");
        check("a suffixed throwable type counts without a frame", traces.size() == 1,
                "traces=" + traces);
    }

    private static void allowedSubstringsAreIgnored() {
        List<String> found = new ArrayList<String>();
        SmokeTest.LogTee.scan("ch.randelshofer.quaqua.QuaquaManager: java.util.MissingResourceException: x\n",
                new String[] {"quaqua"}, found);
        check("an allowed substring suppresses the trace", found.isEmpty(), "found=" + found);
    }

    private static void reportedEntryCarriesTheFirstFrame() {
        List<String> traces = traces(
                "java.lang.NullPointerException: Cannot read field \"toolBar\"",
                "\tat org.alchemy.core.AlcWindow.resizeWindow(AlcWindow.java:186)",
                "\tat org.alchemy.core.AlcWindow.<init>(AlcWindow.java:84)");
        check("the entry names the frame that owns the failure",
                traces.size() == 1
                        && traces.get(0).contains("AlcWindow.resizeWindow(AlcWindow.java:186)"),
                "traces=" + traces);
    }

    private static void causedByIsCollected() {
        List<String> traces = traces(
                "java.lang.RuntimeException: wrapper",
                "\tat org.alchemy.core.AlcUtil.openWebPage(AlcUtil.java:671)",
                "Caused by: java.io.IOException: PARSE",
                "\tat ch.randelshofer.quaqua.util.BinaryPListParser.parseInt(BinaryPListParser.java:231)");
        check("the cause of a trace is reported too", traces.size() == 2, "traces=" + traces);
    }

    private static List<String> traces(String... lines) {
        List<String> found = new ArrayList<String>();
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        SmokeTest.LogTee.scan(text.toString(), new String[0], found);
        return found;
    }

    private static void check(String name, boolean condition, String detail) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL " + name + " :: " + detail);
        }
    }
}
