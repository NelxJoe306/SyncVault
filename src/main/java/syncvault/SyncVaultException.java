package syncvault;

/** General exception indicating a SyncVault error.  For fatal errors, the
 *  result of .getMessage() is the error message to be printed.
 *  @author P. N. Hilfinger
 */
public class SyncVaultException extends RuntimeException {


    /** A SyncVaultException with no message. */
    public SyncVaultException() {
        super();
    }

    /** A SyncVaultException MSG as its message. */
    public SyncVaultException(String msg) {
        super(msg);
    }

}
