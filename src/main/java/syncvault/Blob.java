package syncvault;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.TreeMap;

import static syncvault.Repository.*;
import static syncvault.Utils.join;

public class Blob implements Serializable{
    /**
     * This class is to work as below:
     * 1. store the hash of the relative file
     * 2. serializable for better creating the commit
     */
    /**
     *  fHash The file's hash
     *  file The file
     *  text The content of file
     */
    private String fHash;
    private File file;
    /** Raw file bytes. A String would replace bytes that are not valid UTF-8. */
    private byte[] text;

    private Blob(File f) {
        this.file = f;
        byte[] t = Utils.readContents(f);
        this.fHash = Utils.hash(t);
        this.text = t;
    }

    /**
     * Saves the blob to a file.
     * Store the file's names in the stage
     * help add method works
     *
     * @param name the file to be staged
     *             stageFile store the 'name' content and the name itself is the hash
     *             filename stores all the stage files' name by using newfilename and oldfilename
     */
    public static void saveToStage(String name) throws IOException {
        File f = join(CWD, name);  /* target file to stage*/
        Blob b = new Blob(f);

        Store.unstageRemove(name);
        Store.stageAdd(name, b.fHash);
        Store.putBlob(b.fHash, b.text);
    }

    /**
     * reset the stage after commiting
     */
    public static void resetStage() {
        Store.clearStageAdd();
    }

    /**
     * reset the stage after commiting
     */
    public static void resetRemoval() {
        Store.clearStageRm();
    }
}
