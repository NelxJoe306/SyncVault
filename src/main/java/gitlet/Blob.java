package gitlet;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.TreeMap;

import static gitlet.Repository.*;
import static gitlet.Utils.join;

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
        TreeMap<String, String> m;

        File stageFile = join(stage_DIR, b.fHash);
        File filelist = join(stage_DIR, "files");


        if (Utils.join(removal, name).exists()) {
            Utils.join(removal, name).delete();
        }

        if (filelist.exists()) {
           m = Utils.readObject(filelist, java.util.TreeMap.class);
        } else {
            m = new TreeMap<>();
            filelist.createNewFile();
        }
        m.put(name, b.fHash);
        Utils.writeObject(filelist, m);

        if (!stageFile.exists()) {
            Utils.writeContents(stageFile, b.text);
        }
    }

    /**
     * reset the stage after commiting
     */
    public static void resetStage() {
        File[] files = stage_DIR.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    /**
     * reset the stage after commiting
     */
    public static void resetRemoval() {
        File[] files = removal.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }
}
