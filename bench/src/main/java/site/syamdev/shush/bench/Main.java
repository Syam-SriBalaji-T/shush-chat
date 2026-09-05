package site.syamdev.shush.bench;

import java.util.List;

/**
 * Exits 0 only when every invariant held. A benchmark that reports throughput without asserting
 * correctness measures how fast the system can be wrong.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        BenchOptions options;
        try {
            options = BenchOptions.parse(args);
        } catch (RuntimeException e) {
            System.err.println("shush-bench: " + e.getMessage());
            System.err.println("""

                    usage: java -jar shush-bench.jar --mode=ordering [options]

                      --mode=ordering        send concurrently from both participants and check invariants
                      --conversations=N      independent conversations (default 10)
                      --messages=N           messages per conversation, even (default 50)
                      --base-url=URL         default http://localhost:8080
                      --via=nginx            shorthand for the load-balanced base url
                      --assert-multinode     fail unless at least two nodes served the run
                      --settle-seconds=N     how long to wait for delivery to finish (default 120)
                    """);
            System.exit(2);
            return;
        }

        if (!"ordering".equals(options.mode())) {
            System.err.println("shush-bench: mode '" + options.mode() + "' is not implemented yet");
            System.exit(2);
            return;
        }

        System.out.println("shush-bench :: mode=" + options.mode() + " target=" + options.baseUrl());
        OrderingBench.Result result = new OrderingBench(options).run();
        report(result);
        System.exit(result.passed() ? 0 : 1);
    }

    private static void report(OrderingBench.Result result) {
        double seconds = result.elapsed().toNanos() / 1_000_000_000.0;
        System.out.println();
        System.out.println("---------------------------------------------");
        System.out.printf("messages        %d%n", result.messages());
        System.out.printf("wall clock      %.2fs%n", seconds);
        System.out.printf("throughput      %.0f msg/s end to end%n", result.messages() / seconds);
        if (!result.nodes().isEmpty()) {
            System.out.printf("nodes serving   %s%n", result.nodes());
        }
        System.out.println("---------------------------------------------");
        System.out.println("no gaps in seq            " + verdict(result, "expected seq"));
        System.out.println("identical order observed  " + verdict(result, "disagree on order"));
        System.out.println("no duplicate deliveries   " + verdict(result, "delivered more than once"));
        System.out.println("nothing lost              " + verdict(result, "persisted"));
        System.out.println("---------------------------------------------");

        if (result.passed()) {
            System.out.println("PASS - every invariant held");
            return;
        }
        System.out.println("FAIL - " + result.failures().size() + " invariant violation(s)");
        List<String> failures = result.failures();
        failures.stream().limit(25).forEach(failure -> System.out.println("  * " + failure));
        if (failures.size() > 25) {
            System.out.println("  ... and " + (failures.size() - 25) + " more");
        }
    }

    private static String verdict(OrderingBench.Result result, String marker) {
        return result.failures().stream().anyMatch(failure -> failure.contains(marker)) ? "FAIL" : "ok";
    }
}
