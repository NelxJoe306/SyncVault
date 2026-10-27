package syncvault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;

/**
 * SHA-256 implementation of HashFunction (produces 64-char lowercase hex).
 */
public class Sha256Hash implements HashFunction {

    @Override
    public String name() {
        return "SHA-256";
    }

    @Override
    public int hexLength() {
        return 64;
    }

    @Override
    public String hash(Object... vals) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Object val : vals) {
                if (val instanceof byte[]) {
                    md.update((byte[]) val);
                } else if (val instanceof String) {
                    md.update(((String) val).getBytes(StandardCharsets.UTF_8));
                } else {
                    throw new IllegalArgumentException("improper type to sha256");
                }
            }
            Formatter result = new Formatter();
            for (byte b : md.digest()) {
                result.format("%02x", b);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException excp) {
            throw new IllegalArgumentException("System does not support SHA-256");
        }
    }
}
