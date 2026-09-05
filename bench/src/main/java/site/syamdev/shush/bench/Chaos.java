package site.syamdev.shush.bench;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Kills a replica outright.
 *
 * <p>{@code docker kill}, not {@code docker stop}: a SIGKILL gives the process no chance to
 * drain sockets, commit consumer offsets or tidy anything up. Correctness must not depend on a
 * dying node behaving politely, and a graceful stop would quietly test the easy case.
 */
final class Chaos {

    private Chaos() {
    }

    static void kill(String service) throws IOException, InterruptedException {
        String container = containerNameOf(service);
        Process process = new ProcessBuilder("docker", "kill", container)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes()).trim();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("docker kill " + container + " did not return");
        }
        if (process.exitValue() != 0) {
            throw new IOException("could not kill " + container + ": " + output
                    + " (is the replicas overlay up?)");
        }
        System.out.printf("!! killed %s%n", container);
    }

    static void restart(String service) throws IOException, InterruptedException {
        new ProcessBuilder("docker", "start", containerNameOf(service))
                .redirectErrorStream(true)
                .start()
                .waitFor(60, TimeUnit.SECONDS);
    }

    /** Compose names containers {@code <project>-<service>-<index>}; the project here is "shush". */
    private static String containerNameOf(String service) {
        return "shush-" + service + "-1";
    }
}
