package syncvault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Commit ids are the SHA-256 of the SVC1 encoding, not Java serialization. */
public class CommitIdTest {

    @AfterEach
    public void tearDown() {
        Store.closeDefault();
    }

    @Test
    public void initialCommitIdIsUnchanged(@TempDir Path tempDir) throws Exception {
        Store.useDir(tempDir.toFile());
        Store.createSchema();
        /* Initial commit id: SHA-256 of the SVC1 encoding of timestamp 0, message
           "initial commit", no parents, and an empty file map. */
        assertEquals("09dcbc6680373bdb509a1d45ba888bf89ade8780bbfe9bba9e19fcbcf1ef4d02",
                Commit.first_commit());
    }
}
