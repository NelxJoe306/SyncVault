package syncvault;

import java.io.File;
import java.io.IOException;

import static syncvault.Repository.*;

/** Driver class for SyncVault, a subset of the Git version-control system.
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
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Please enter a command.");
            System.exit(0);
        }

        String firstArg = args[0];
        if (firstArg.equals("init")) {
            if (args.length != 1) {
                System.out.println("Incorrect operands.");
                System.exit(0);
            }
            Repository.init();
            return;
        }
        if (firstArg.equals("bench")) {
            String[] benchArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
            try {
                Benchmark.main(benchArgs);
            } catch (Exception e) {
                throw new SyncVaultException(e.getMessage());
            }
            return;
        }

        if (!Repository.SYNCVAULT_DIR.exists()) {
            System.out.println("Not in an initialized SyncVault directory.");
            System.exit(0);
        }

        String curr_bran_head = Store.getHead();
        String a = Store.getRef(curr_bran_head);
        if (a != null) {
            currHead = a;
        }

        switch (firstArg) {
            case "add":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.add(args[1]);
                break;
            case "commit":
                if (args.length != 2 || args[1].trim().isEmpty()) {
                    System.out.println("Please enter a commit message.");
                    System.exit(0);
                }
                Commit.commitInCurrBran(args[1]);
                break;
            case "rm":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.rm(args[1]);
                break;
            case "log":
                if (args.length != 1) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.log();
                break;
            case "global-log":
                if (args.length != 1) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.global_log();
                break;
            case "find":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.find(args[1]);
                break;
            case "status":
                if (args.length != 1) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
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
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                break;
            case "branch":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Commit.branch(args[1]);
                break;
            case "rm-branch":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Commit.rm_branch(args[1]);
                break;
            case "reset":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.reset(args[1]);
                break;
            case "merge":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.merge(args[1]);
                break;
            case "resolve":
                if (args.length != 3 || (!args[2].equals("--ours")
                        && !args[2].equals("--theirs")
                        && !args[2].equals("--combined"))) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                try {
                    if (args[1].equals("--all")) {
                        Repository.resolveAll(args[2]);
                    } else {
                        Repository.resolve(args[1], args[2]);
                    }
                } catch (SyncVaultException e) {
                    System.out.println(e.getMessage());
                    System.exit(0);
                }
                break;
            case "add-remote":
                if (args.length != 3) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.add_remote(args[1], args[2]);
                break;
            case "rm-remote":
                if (args.length != 2) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
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
                            System.out.println("Incorrect operands.");
                            System.exit(0);
                        }
                        Repository.web(port).await();
                    } catch (NumberFormatException e) {
                        System.out.println("Incorrect operands.");
                        System.exit(0);
                    }
                } else {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                break;
            case "gc":
                if (args.length == 1) {
                    Repository.gc();
                } else if (args.length == 2) {
                    long graceMs = parseGraceArgument(args[1]);
                    Repository.gc(graceMs);
                } else {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                break;
            default:
                System.out.println("No command with that name exists.");
                System.exit(0);
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
                System.out.println("Incorrect operands.");
                System.exit(0);
                return;
            } else {
                positionals.add(arg);
            }
        }

        if (positionals.size() != 2) {
            System.out.println("Incorrect operands.");
            System.exit(0);
            return;
        }

        String remoteName = positionals.get(0);
        String branchName = positionals.get(1);
        try {
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
        } catch (SyncVaultException e) {
            System.out.println(e.getMessage());
            System.exit(0);
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
                System.out.println("Incorrect operands.");
                System.exit(0);
                return;
            } else if (port == null) {
                try {
                    int parsed = Integer.parseInt(arg);
                    if (parsed < 0 || parsed > 65535) {
                        System.out.println("Incorrect operands.");
                        System.exit(0);
                        return;
                    }
                    port = parsed;
                } catch (NumberFormatException e) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                    return;
                }
            } else {
                System.out.println("Incorrect operands.");
                System.exit(0);
                return;
            }
        }

        int targetPort = (port != null) ? port : NettyServer.DEFAULT_PORT;
        try {
            Repository.serve(targetPort, token, tlsCert, tlsKey).await();
        } catch (SyncVaultException e) {
            System.out.println(e.getMessage());
            System.exit(0);
        }
    }

    static long parseGraceArgument(String arg) {
        if (arg == null || arg.isBlank()) {
            System.out.println("Incorrect operands.");
            System.exit(0);
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
        System.out.println("Incorrect operands.");
        System.exit(0);
        return 0L;
    }
}
