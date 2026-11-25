package io.github.green4j.timebase.perftests;

import java.util.Arrays;
import java.util.List;

/**
 * Entry point: {@code run} and {@code report} for people, the other commands for the client processes the runner
 * starts.
 */
public final class Main {

    static final List<String> RUN_OPTIONS = List.of("profile", "depth", "load", "topology", "baseline", "candidate", "bench",
            "set", "name", "out", "port", "serverJava", "clientJava", "seed", "dryRun", "print");
    static final List<String> REPORT_OPTIONS = List.of("results", "baseline");

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        // The TimeBase client's JAXB fails on JDK 17+ without it
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        if (argv.length == 0) {
            usage();
            System.exit(2);
        }
        final Args args = Args.parse(Arrays.copyOfRange(argv, 1, argv.length));
        switch (argv[0]) {
            case "run":
                if (!Runner.run(args.only("run", RUN_OPTIONS)))
                    System.exit(1);
                break;
            case "report":
                Report.run(args.only("report", REPORT_OPTIONS));
                break;
            case "profiles":
                args.only("profiles", List.of());
                Profiles.list();
                break;
            case "writer":
                WriterCommand.run(args);
                break;
            case "readers":
                ReadersCommand.run(args);
                break;
            case "latency":
                LatencyCommand.run(args);
                break;
            case "verify":
                VerifyCommand.run(args);
                break;
            default:
                usage();
                System.exit(2);
        }
    }

    private static void usage() {
        System.err.println("Usage: perftests run --profile=<latency|throughput|correctness|sanity|file.json> [--depth=<depth>]");
        System.err.println("                     [--load=<load>[,<load>...]] [--topology=one2one|one2three|one2six]");
        System.err.println("                     [--baseline=[id=](git:…|dist:…|image:…|url:…)]");
        System.err.println("                     [--candidate=[id=](git:…|dist:…|image:…|url:…)]... [--bench=<file.json>]");
        System.err.println("                     [--set=[suite.]key=v1[,v2...]]...");
        System.err.println("                     [--name=<campaign>] [--out=results] [--port=18011] [--serverJava=<java>] [--clientJava=<java>]");
        System.err.println("                     [--seed=<n>] [--dryRun] [--print]");
        System.err.println("         git:<repo>@<ref|WORKTREE> | dist:<dir>[+overlay:<jar>] | image:<tag> | url:dxtick://<host>:<port>");
        System.err.println("         --baseline is required unless the bench file gives a baseline");
        System.err.println("       perftests profiles");
        System.err.println("       perftests report --results=<results/campaign> [--baseline=<id>]");
    }
}
