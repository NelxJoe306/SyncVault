package syncvault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Commit ids must not change when the storage layer changes. */
public class CommitIdTest {

    @AfterEach
    public void tearDown() {
        Store.closeDefault();
    }

    @Test
    public void initialCommitIdIsUnchanged(@TempDir Path tempDir) throws Exception {
        Store.useDir(tempDir.toFile());
        Store.createSchema();
        /* Initial commit id for SyncVault (SHA-256). */
        assertEquals("860367ca764a82fc4d959fdc3b2f476ea0165d2a89dd0b7d23911062742b2198",
                Commit.first_commit());
    }
}
