package gitlet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Sha256HashTest {

    @Test
    public void testHashFormatAndEquality() {
        Sha256Hash hasher = new Sha256Hash();
        String sample = "sample input for hash testing";

        String hash1 = hasher.hash(sample);
        String hash2 = hasher.hash(sample);

        // Hashing produces a 64-char lowercase hex string
        assertEquals(64, hash1.length());
        assertTrue(hash1.matches("^[0-9a-f]{64}$"));

        // Hashing the same input produces equal results
        assertEquals(hash1, hash2);
    }
}
