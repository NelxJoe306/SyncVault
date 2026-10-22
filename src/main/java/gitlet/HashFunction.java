package gitlet;

import java.util.List;

/**
 * Interface for pluggable hash algorithms in Gitlet.
 */
public interface HashFunction {

    /** Returns the canonical name of the hash algorithm (e.g., "SHA-1", "SHA-256"). */
    String name();

    /** Returns the length of the hex string produced by this hash function. */
    int hexLength();

    /**
     * Returns the hash of the concatenation of VALS, which may be
     * any mixture of byte arrays and Strings.
     */
    String hash(Object... vals);

    /**
     * Returns the hash of the concatenation of items in VALS.
     */
    default String hash(List<Object> vals) {
        return hash(vals.toArray(new Object[0]));
    }
}
