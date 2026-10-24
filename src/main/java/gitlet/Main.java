package gitlet;

import java.io.File;
import java.io.IOException;

import static gitlet.Repository.*;

/** Driver class for Gitlet, a subset of the Git version-control system.
 *  @author JOE
 */
public class Main {

    /** Usage: java gitlet.Main ARGS, where ARGS contains
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
     * add-remote
     * rm-remote
     * push
     * fetch
     * pull
     * serve
     * web
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

        if (!Repository.GITLET_DIR.exists()) {
            System.out.println("Not in an initialized Gitlet directory.");
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
                if (args.length != 3) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.push(args[1], args[2]);
                break;
            case "fetch":
                if (args.length != 3) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.fetch(args[1], args[2]);
                break;
            case "pull":
                if (args.length != 3) {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                Repository.pull(args[1], args[2]);
                break;
            case "serve":
                if (args.length == 1) {
                    Repository.serve(NettyServer.DEFAULT_PORT).await();
                } else if (args.length == 2) {
                    try {
                        Repository.serve(Integer.parseInt(args[1])).await();
                    } catch (NumberFormatException e) {
                        System.out.println("Incorrect operands.");
                        System.exit(0);
                    }
                } else {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
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
            default:
                System.out.println("No command with that name exists.");
                System.exit(0);
        }
    }
}
