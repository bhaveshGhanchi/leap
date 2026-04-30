package com.leap;

import java.util.Arrays;

import com.leap.benchmark.Benchmark;
import com.leap.client.Client;
import com.leap.server.Server;

public class App {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "send":
            case "client":
                Client.main(rest);
                break;
            case "receive":
            case "server":
                Server.main(rest);
                break;
            case "benchmark":
            case "bench":
                Benchmark.main(rest);
                break;
            default:
                System.err.println("Unknown command: " + args[0]);
                printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("LEAP: Loss-aware End-to-end Acknowledged Protocol");
        System.out.println("Reliable file transfer over UDP.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  leap send <file> --to <host:port> [--window <n>] [--chunk <bytes>] [--debug]");
        System.out.println("  leap receive --port <n> [--output <dir>] [--loss <0.0-1.0>]");
        System.out.println("  leap benchmark [--loss-mode app|proxy|kernel] [--sizes ...] [--trials n]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  leap send input.txt --to 192.168.1.10:4040");
        System.out.println("  leap receive --port 4040 --output received/");
        System.out.println("  leap benchmark --loss-mode proxy --sizes 10m --trials 3");
        System.out.println();
        System.out.println("Legacy verbs 'client' and 'server' are still accepted.");
        System.out.println("Run 'leap send --help', 'leap receive --help', or 'leap benchmark --help'.");
    }
}
