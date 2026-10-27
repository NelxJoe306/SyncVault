package syncvault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI process status for user-facing command failures. */
public class CommandExitTest {

    @Test
    public void userErrorsExitOneAndKeepMessageText(@TempDir Path dir) throws Exception {
        Cli missing = run(dir);
        assertEquals(1, missing.exit, missing.output);
        assertEquals("Please enter a command." + System.lineSeparator(), missing.output);

        Cli uninitialized = run(dir, "log");
        assertEquals(1, uninitialized.exit, uninitialized.output);
        assertTrue(uninitialized.output.contains("Not in an initialized SyncVault directory."),
                uninitialized.output);

        Cli init = run(dir, "init");
        assertEquals(0, init.exit, init.output);

        Cli again = run(dir, "init");
        assertEquals(1, again.exit, again.output);
        assertTrue(again.output.contains(
                "A SyncVault version-control system already exists in the current directory."),
                again.output);

        Cli operands = run(dir, "add");
        assertEquals(1, operands.exit, operands.output);
        assertTrue(operands.output.contains("Incorrect operands."), operands.output);

        Cli unknown = run(dir, "nope");
        assertEquals(1, unknown.exit, unknown.output);
        assertTrue(unknown.output.contains("No command with that name exists."), unknown.output);

        Cli noCommit = run(dir, "commit", "   ");
        assertEquals(1, noCommit.exit, noCommit.output);
        assertTrue(noCommit.output.contains("Please enter a commit message."), noCommit.output);
    }

    private Cli run(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("syncvault.Main");
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new Cli(exit, output);
    }

    private record Cli(int exit, String output) {}
}
