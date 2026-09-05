package site.syamdev.shush.bench;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * @param conversations   independent conversations, each with two participants
 * @param messages        messages per conversation, split evenly between the two participants
 * @param assertMultinode fail unless at least two distinct nodes served the run; without it a
 *                        run through a load balancer can silently prove nothing (Phase 3)
 */
record BenchOptions(String mode,
                    String baseUrl,
                    int conversations,
                    int messages,
                    boolean assertMultinode,
                    String killNode,
                    String composeProject,
                    int killAtSecond,
                    Duration sendWindow,
                    Duration settleTimeout) {

    static BenchOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String[] parts = arg.substring(2).split("=", 2);
            flags.put(parts[0], parts.length > 1 ? parts[1] : "true");
        }

        int conversations = Integer.parseInt(flags.getOrDefault("conversations", "10"));
        int messages = Integer.parseInt(flags.getOrDefault("messages", "50"));
        if (conversations < 1) {
            throw new IllegalArgumentException("--conversations must be at least 1");
        }
        if (messages < 2 || messages % 2 != 0) {
            throw new IllegalArgumentException("--messages must be even and at least 2, so both "
                    + "participants send the same number");
        }

        return new BenchOptions(
                flags.getOrDefault("mode", "ordering"),
                stripTrailingSlash(flags.getOrDefault("base-url",
                        "nginx".equals(flags.get("via")) ? "http://localhost:8081" : "http://localhost:8080")),
                conversations,
                messages,
                Boolean.parseBoolean(flags.getOrDefault("assert-multinode", "false")),
                flags.get("kill-node"),
                flags.getOrDefault("compose-project", "shush"),
                Integer.parseInt(flags.getOrDefault("at-second", "15")),
                // Chaos runs spread their sends over a window by default. Firing everything as
                // fast as possible finishes in a second or two, so a kill scheduled for later
                // lands after the last send and tests nothing about sending through a failure.
                Duration.ofSeconds(Long.parseLong(flags.getOrDefault("send-seconds",
                        "chaos".equals(flags.getOrDefault("mode", "ordering")) ? "40" : "0"))),
                Duration.ofSeconds(Long.parseLong(flags.getOrDefault("settle-seconds", "180"))));
    }

    String websocketUrl() {
        return baseUrl.replaceFirst("^http", "ws") + "/ws/chat";
    }

    int messagesPerParticipant() {
        return messages / 2;
    }

    int totalMessages() {
        return conversations * messages;
    }

    /** Gap between one participant's sends, or zero to send as fast as the socket allows. */
    Duration sendInterval() {
        return sendWindow.isZero()
                ? Duration.ZERO
                : sendWindow.dividedBy(messagesPerParticipant());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
