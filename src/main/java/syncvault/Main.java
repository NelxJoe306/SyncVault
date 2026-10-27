package syncvault;

import java.io.File;
import java.io.IOException;

import static syncvault.Repository.*;

/** Driver class for SyncVault, a subset of the Git version-control system.
 *  Command failures throw {@link CommandException} (or another
 *  {@link SyncVaultException} with a user-facing message). This class is the
 *  only caller of {@code System.exit}: it prints that message and exits 1
 *  on errors. The Netty server does not use this path.
 *  @author JOE
 */
public class Main {

    /** Usage: java syncvault.Main ARGS, where ARGS contains
     * init
     * add
     * commit
     * rm
     * log
     * global-log
     * find
     * status
     * checkout
     * branch
     * rm-branch
     * reset
     * merge
     * resolve
     * add-remote
     * rm-remote
     * push
     * fetch
     * pull
     * serve
     * web
     * gc
     * gc
     * fsck
     * abort
     * continue
     * recover
     */
    public static void main(String[] args) throws IOException {
        try {
            run(args);
        } catch (SyncVaultException e) {
            String msg = e.getMessage();
            if (msg != null && !msg.isEmpty()) {
                System.out.println(msg);
            }
            int code = (e instanceof CommandException ce) ? ce.exitCode() : 1;
            System.exit(code);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length == 0) {
            throw new CommandException("Please enter a command.");
        }

        String firstArg = args[0];
        if (firstArg.equals("init")) {
            if (args.length != 1) {
                throw new CommandException("Incorrect operands.");
            }
            Repository.init();
            return;
        }
        if (firstArg.equals("bench")) {
            String[] benchArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
            try {
                Benchmark.main(benchArgs);
            } catch (SyncVaultException e) {
                throw e;
            } catch (Exception e) {
                throw new CommandException(e.getMessage());
            }
            return;
        }

        File syncDir = Repository.syncvaultDirForSession(Store.getDefault());
        if (!syncDir.exists()) {
            throw new CommandException("Not in an initialized SyncVault directory.");
        }

        if (firstArg.equals("abort") || firstArg.equals("--abort")) {
            if (!WorkspaceJournal.hasPending()) {
                System.out.println("No interrupted workspace update to abort.");
                return;
            }
            WorkspaceJournal.abort();
            System.out.println("Interrupted workspace update aborted.");
            return;
        }
        if (firstArg.equals("continue") || firstArg.equals("--continue")) {
            if (!WorkspaceJournal.hasPending()) {
                System.out.println("No interrupted workspace update to continue.");
                return;
            }
            WorkspaceJournal.continueOp();
            System.out.println("Interrupted workspace update continued.");
            return;
        }
        if (firstArg.equals("recover")) {
            if (!WorkspaceJournal.hasPending()) {
                System.out.println("No interrupted workspace update to recover.");
                return;
            }
            if (args.length > 1 && (args[1].equals("--continue") || args[1].equals("continue"))) {
                WorkspaceJournal.continueOp();
                System.out.println("Interrupted workspace update continued.");
            } else if (args.length > 1 && (args[1].equals("--abort") || args[1].equals("abort"))) {
                WorkspaceJournal.abort();
                System.out.println("Interrupted workspace update aborted.");
            } else {
                System.out.println("Usage: syncvault recover --abort | --continue");
            }
            return;
        }

        if (WorkspaceJournal.hasPending()) {
            String autoRecover = System.getenv("SYNCVAULT_AUTO_RECOVER");
            if ("continue".equalsIgnoreCase(autoRecover)) {
                WorkspaceJournal.continueOp();
                return;
            } else if ("abort".equalsIgnoreCase(autoRecover)) {
                WorkspaceJournal.abort();
                return;
            } else {
                throw new CommandException(
                        "An interrupted workspace update was detected. Run 'syncvault abort' or 'syncvault continue'.");
            }
        }

        String curr_bran_head = Store.getHead();
        String a = Store.getRef(curr_bran_head);
        if (a != null) {
            currHead = a;
        }

        switch (firstArg) {
            case "add":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.add(args[1]);
                break;
            case "commit":
                if (args.length != 2 || args[1].trim().isEmpty()) {
                    throw new CommandException("Please enter a commit message.");
                }
                Commit.commitInCurrBran(args[1]);
                break;
            case "rm":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.rm(args[1]);
                break;
            case "log":
                if (args.length != 1) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.log();
                break;
            case "global-log":
                if (args.length != 1) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.global_log();
                break;
            case "find":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.find(args[1]);
                break;
            case "status":
                if (args.length != 1) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.status();
                break;
            case "checkout":
                if (args.length == 3 && args[1].equals("--")) {
                    Repository.check_file(args[2]);
                } else if (args.length == 4 && args[2].equals("--")) {
                    Repository.check_id(args[1], args[3]);
                } else if (args.length == 2) {
                    Repository.check_branch(args[1]);
                } else {
                    throw new CommandException("Incorrect operands.");
                }
                break;
            case "branch":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Commit.branch(args[1]);
                break;
            case "rm-branch":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Commit.rm_branch(args[1]);
                break;
            case "reset":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.reset(args[1]);
                break;
            case "merge":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.merge(args[1]);
                break;
            case "resolve":
                if (args.length != 3 || (!args[2].equals("--ours")
                        && !args[2].equals("--theirs")
                        && !args[2].equals("--combined"))) {
                    throw new CommandException("Incorrect operands.");
                }
                if (args[1].equals("--all")) {
                    Repository.resolveAll(args[2]);
                } else {
                    Repository.resolve(args[1], args[2]);
                }
                break;
            case "add-remote":
                if (args.length != 3) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.add_remote(args[1], args[2]);
                break;
            case "rm-remote":
                if (args.length != 2) {
                    throw new CommandException("Incorrect operands.");
                }
                Repository.rm_remote(args[1]);
                break;
            case "push":
            case "fetch":
            case "pull":
                handleRemoteSync(firstArg, args);
                break;
            case "serve":
                handleServe(args);
                break;
            case "web":
                if (args.length == 1) {
                    Repository.web(WebServer.DEFAULT_PORT).await();
                } else if (args.length == 2) {
                    try {
                        int port = Integer.parseInt(args[1]);
                        if (port < 0 || port > 65535) {
                            throw new CommandException("Incorrect operands.");
                        }
                        Repository.web(port).await();
                    } catch (NumberFormatException e) {
                        throw new CommandException("Incorrect operands.");
                    }
                } else {
                    throw new CommandException("Incorrect operands.");
                }
                break;
            case "gc":
                if (args.length == 1) {
                    Repository.gc();
                } else if (args.length == 2) {
                    long graceMs = parseGraceArgument(args[1]);
                    Repository.gc(graceMs);
                } else {
                    throw new CommandException("Incorrect operands.");
                }
                break;
            case "fsck":
                if (args.length != 1) {
                    throw new CommandException("Incorrect operands.");
                }
                if (!Repository.fsck().ok()) {
                    throw new CommandException(null, 1);
                }
                break;
            default:
                throw new CommandException("No command with that name exists.");
        }
    }

    private static void handleRemoteSync(String cmd, String[] args) throws IOException {
        java.util.List<String> positionals = new java.util.ArrayList<>();
        String token = null;
        File tlsCa = null;
        boolean tlsInsecure = false;
        boolean forceTls = false;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--token=")) {
                token = arg.substring("--token=".length());
            } else if (arg.equals("--token") && i + 1 < args.length) {
                token = args[++i];
            } else if (arg.startsWith("--tls-ca=")) {
                tlsCa = new File(arg.substring("--tls-ca=".length()));
            } else if (arg.equals("--tls-ca") && i + 1 < args.length) {
                tlsCa = new File(args[++i]);
            } else if (arg.equals("--tls-insecure")) {
                tlsInsecure = true;
            } else if (arg.equals("--tls")) {
                forceTls = true;
            } else if (arg.startsWith("--")) {
                throw new CommandException("Incorrect operands.");
            } else {
                positionals.add(arg);
            }
        }

        if (positionals.size() != 2) {
            throw new CommandException("Incorrect operands.");
        }

        String remoteName = positionals.get(0);
        String branchName = positionals.get(1);
        switch (cmd) {
            case "push":
                Repository.push(remoteName, branchName, token, tlsCa, tlsInsecure, forceTls);
                break;
            case "fetch":
                Repository.fetch(remoteName, branchName, token, tlsCa, tlsInsecure, forceTls);
                break;
            case "pull":
                Repository.pull(remoteName, branchName, token, tlsCa, tlsInsecure, forceTls);
                break;
            default:
                break;
        }
    }

    private static void handleServe(String[] args) {
        Integer port = null;
        String token = null;
        File tlsCert = null;
        File tlsKey = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--token=")) {
                token = arg.substring("--token=".length());
            } else if (arg.equals("--token") && i + 1 < args.length) {
                token = args[++i];
            } else if (arg.startsWith("--tls-cert=")) {
                tlsCert = new File(arg.substring("--tls-cert=".length()));
            } else if (arg.equals("--tls-cert") && i + 1 < args.length) {
                tlsCert = new File(args[++i]);
            } else if (arg.startsWith("--tls-key=")) {
                tlsKey = new File(arg.substring("--tls-key=".length()));
            } else if (arg.equals("--tls-key") && i + 1 < args.length) {
                tlsKey = new File(args[++i]);
            } else if (arg.startsWith("--")) {
                throw new CommandException("Incorrect operands.");
            } else if (port == null) {
                try {
                    int parsed = Integer.parseInt(arg);
                    if (parsed < 0 || parsed > 65535) {
                        throw new CommandException("Incorrect operands.");
                    }
                    port = parsed;
                } catch (NumberFormatException e) {
                    throw new CommandException("Incorrect operands.");
                }
            } else {
                throw new CommandException("Incorrect operands.");
            }
        }

        int targetPort = (port != null) ? port : NettyServer.DEFAULT_PORT;
        Repository.serve(targetPort, token, tlsCert, tlsKey).await();
    }

    static long parseGraceArgument(String arg) {
        if (arg == null || arg.isBlank()) {
            throw new CommandException("Incorrect operands.");
        }
        try {
            if (arg.startsWith("--grace-seconds=")) {
                long sec = Long.parseLong(arg.substring("--grace-seconds=".length()).trim());
                if (sec < 0) throw new NumberFormatException();
                return sec * 1000L;
            } else if (arg.startsWith("--grace-ms=")) {
                long ms = Long.parseLong(arg.substring("--grace-ms=".length()).trim());
                if (ms < 0) throw new NumberFormatException();
                return ms;
            } else if (arg.startsWith("--grace=")) {
                long val = Long.parseLong(arg.substring("--grace=".length()).trim());
                if (val < 0) throw new NumberFormatException();
                return val;
            } else if (arg.matches("^\\d+$")) {
                return Long.parseLong(arg.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        throw new CommandException("Incorrect operands.");
    }
}
