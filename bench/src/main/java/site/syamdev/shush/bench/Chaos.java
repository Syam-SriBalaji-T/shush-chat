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

    static void kill(String project, String service) throws IOException, InterruptedException {
        String container = containerNameOf(project, service);
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

    static void restart(String project, String service) throws IOException, InterruptedException {
        new ProcessBuilder("docker", "start", containerNameOf(project, service))
                .redirectErrorStream(true)
                .start()
                .waitFor(60, TimeUnit.SECONDS);
    }

    /**
     * Compose names containers {@code <project>-<service>-<index>}. The project defaults to the
     * directory name, so it is a flag rather than a constant -- a stack brought up from a clone
     * in a differently-named directory is still killable.
     */
    private static String containerNameOf(String project, String service) {
        return project + "-" + service + "-1";
    }
}
