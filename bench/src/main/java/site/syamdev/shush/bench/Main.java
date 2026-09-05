package site.syamdev.shush.bench;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Exits 0 only when every invariant held. A benchmark that reports throughput without asserting
 * correctness measures how fast the system can be wrong.
 */
public final class Main {

    private static final int MAX_REPORTED_FAILURES = 25;

    public static void main(String[] args) throws Exception {
        BenchOptions options;
        try {
            options = BenchOptions.parse(args);
        } catch (RuntimeException e) {
            System.err.println("shush-bench: " + e.getMessage());
            usage();
            System.exit(2);
            return;
        }

        System.out.println("shush-bench :: mode=" + options.mode() + " target=" + options.baseUrl());

        switch (options.mode()) {
            case "ordering" -> {
                OrderingBench.Result result = new OrderingBench(options).run();
                reportOrdering(result);
                System.exit(result.passed() ? 0 : 1);
            }
            case "chaos" -> {
                if (options.killNode() == null) {
                    System.err.println("shush-bench: --mode=chaos needs --kill-node=<compose service>");
                    usage();
                    System.exit(2);
                    return;
                }
                System.out.printf("will kill %s at second %d%n",
                        options.killNode(), options.killAtSecond());
                ChaosBench.Result result = new ChaosBench(options).run();
                reportChaos(result);
                System.exit(result.passed() ? 0 : 1);
            }
            default -> {
                System.err.println("shush-bench: unknown mode '" + options.mode() + "'");
                usage();
                System.exit(2);
            }
        }
    }

    private static void reportOrdering(OrderingBench.Result result) {
        header(result.messages(), result.elapsed(), result.nodes());
        System.out.println("---------------------------------------------");
        System.out.println("no gaps in seq            " + verdict(result.failures(), "expected seq"));
        System.out.println("identical order observed  " + verdict(result.failures(), "disagree on order"));
        System.out.println("no duplicate deliveries   " + verdict(result.failures(), "delivered more than once"));
        System.out.println("nothing lost              " + verdict(result.failures(), "persisted"));
        System.out.println("---------------------------------------------");
        verdictLines(result.failures(), result.passed());
    }

    private static void reportChaos(ChaosBench.Result result) {
        header(result.messages(), result.elapsed(), result.nodes());
        System.out.printf("reconnects      %d socket reconnection(s) after the kill%n", result.reconnects());
        System.out.printf("retransmits     %d message(s) resent with the same clientMsgId%n",
                result.retransmissions());
        System.out.println("---------------------------------------------");
        System.out.println("no reordering across the kill   " + verdict(result.failures(), "received seq"));
        System.out.println("no duplicate deliveries         " + verdict(result.failures(), "delivered more than once"));
        System.out.println("nothing lost                    " + verdict(result.failures(), "persisted messages numbered"));
        System.out.println("every send in the log once      " + verdict(result.failures(), "not in the log"));
        System.out.println("resume returns what was missed  " + verdict(result.failures(), "resumed from seq"));
        System.out.println("the kill actually disturbed it  " + verdict(result.failures(), "proves nothing"));
        System.out.println("---------------------------------------------");
        verdictLines(result.failures(), result.passed());
    }

    private static void header(int messages, Duration elapsed, Set<String> nodes) {
        double seconds = elapsed.toNanos() / 1_000_000_000.0;
        System.out.println();
        System.out.println("---------------------------------------------");
        System.out.printf("messages        %d%n", messages);
        System.out.printf("wall clock      %.2fs%n", seconds);
        System.out.printf("throughput      %.0f msg/s end to end%n", messages / seconds);
        if (!nodes.isEmpty()) {
            System.out.printf("nodes serving   %s%n", nodes);
        }
    }

    private static void verdictLines(List<String> failures, boolean passed) {
        if (passed) {
            System.out.println("PASS - every invariant held");
            return;
        }
        System.out.println("FAIL - " + failures.size() + " invariant violation(s)");
        failures.stream().limit(MAX_REPORTED_FAILURES)
                .forEach(failure -> System.out.println("  * " + failure));
        if (failures.size() > MAX_REPORTED_FAILURES) {
            System.out.println("  ... and " + (failures.size() - MAX_REPORTED_FAILURES) + " more");
        }
    }

    private static String verdict(List<String> failures, String marker) {
        return failures.stream().anyMatch(failure -> failure.contains(marker)) ? "FAIL" : "ok";
    }

    private static void usage() {
        System.err.println("""

                usage: java -jar shush-bench.jar --mode=ordering|chaos [options]

                  --mode=ordering        send concurrently from both participants, check invariants
                  --mode=chaos           the same, with a replica killed mid-run
                  --conversations=N      independent conversations (default 10)
                  --messages=N           messages per conversation, even (default 50)
                  --base-url=URL         default http://localhost:8080
                  --via=nginx            shorthand for the load-balanced base url
                  --assert-multinode     fail unless at least two nodes served the run
                  --kill-node=SERVICE    compose service to kill, e.g. api-2 (chaos mode)
                  --at-second=N          when to kill it (default 15)
                  --settle-seconds=N     how long to wait for delivery to finish (default 120)
                """);
    }
}
