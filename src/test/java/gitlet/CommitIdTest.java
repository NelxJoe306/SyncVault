package gitlet;

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
        /* Same id the file-based Gitlet produced for the initial commit (SHA-256). */
        assertEquals("15fd3b342ae2da4040205a401738f036a98456e28db0f20dd233be944e99dd01",
                Commit.first_commit());
    }
}
