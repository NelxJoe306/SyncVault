package syncvault;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Big-endian length-prefixed fields shared by the commit and sync encodings.
 * Readers are only used on in-memory buffers ({@code ByteArrayInputStream}),
 * where {@link DataInputStream#available()} is the number of bytes left.
 */
final class BinaryFields {

    static final int MAX_STRING_BYTES = 8 * 1024 * 1024;
    static final int MAX_BYTES = 64 * 1024 * 1024;
    static final int MAX_COUNT = 1_000_000;

    private BinaryFields() {
    }

    static void rejectJavaSerialization(byte[] data, String what) {
        if (data != null && data.length >= 2
                && (data[0] & 0xFF) == 0xAC
                && (data[1] & 0xFF) == 0xED) {
            throw new SyncVaultException(
                    "refusing Java serialization of " + what + "; expected explicit field encoding");
        }
    }

    static void writeNullableString(DataOutputStream out, String value) {
        try {
            if (value == null) {
                out.writeByte(0);
            } else {
                out.writeByte(1);
                writeString(out, value);
            }
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static void writeString(DataOutputStream out, String value) {
        if (value == null) {
            throw new SyncVaultException("missing string field");
        }
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STRING_BYTES) {
                throw new SyncVaultException("string field is too long");
            }
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static String readNullableString(DataInputStream in) {
        int flag = readUnsignedByte(in);
        if (flag == 0) {
            return null;
        }
        if (flag != 1) {
            throw new SyncVaultException("invalid string flag");
        }
        return readString(in);
    }

    static String readString(DataInputStream in) {
        try {
            int len = in.readInt();
            if (len < 0 || len > MAX_STRING_BYTES || len > in.available()) {
                throw new SyncVaultException("invalid string length");
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            return decodeUtf8(buf);
        } catch (SyncVaultException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static void writeByteArray(DataOutputStream out, byte[] value) {
        if (value == null) {
            throw new SyncVaultException("missing byte field");
        }
        if (value.length > MAX_BYTES) {
            throw new SyncVaultException("byte field is too long");
        }
        try {
            out.writeInt(value.length);
            out.write(value);
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static byte[] readByteArray(DataInputStream in) {
        try {
            int len = in.readInt();
            if (len < 0 || len > MAX_BYTES || len > in.available()) {
                throw new SyncVaultException("invalid byte length");
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            return buf;
        } catch (SyncVaultException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static int readUnsignedByte(DataInputStream in) {
        try {
            return in.readUnsignedByte();
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static int readUnsignedShort(DataInputStream in) {
        try {
            return in.readUnsignedShort();
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static long readLong(DataInputStream in) {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    static int readCount(DataInputStream in) {
        try {
            int count = in.readInt();
            if (count < 0 || count > MAX_COUNT || count > in.available()) {
                throw new SyncVaultException("invalid element count");
            }
            return count;
        } catch (SyncVaultException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncVaultException("truncated field encoding");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new SyncVaultException("field is not valid UTF-8");
        }
    }
}
