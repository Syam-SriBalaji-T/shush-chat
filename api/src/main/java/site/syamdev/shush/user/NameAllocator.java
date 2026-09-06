package site.syamdev.shush.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Generates "Adjective Noun" display names from two closed word lists (plan.md 3.9).
 *
 * <p>Allocation is optimistic: propose a name, let the unique index on
 * {@code users.display_name} decide. Checking for availability first would be a
 * check-then-act race across nodes, and the index has to be the authority anyway.
 */
@Component
public class NameAllocator {

    private static final int RANDOM_ATTEMPTS = 5;
    private static final int MAX_SUFFIX = 9999;

    private final List<String> adjectives;
    private final List<String> nouns;

    NameAllocator(@Value("classpath:names/adjectives.txt") Resource adjectives,
                  @Value("classpath:names/nouns.txt") Resource nouns) {
        this.adjectives = readWords(adjectives);
        this.nouns = readWords(nouns);
    }

    /**
     * @param isTaken decides whether a candidate is already in use; the caller supplies it so
     *                the authority stays the database rather than anything cached in this node.
     */
    public String allocate(Predicate<String> isTaken) {
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            String candidate = randomName();
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }

        // The random space is 40,000 wide, so repeated collisions mean it is genuinely
        // crowded rather than unlucky. Walk a suffix instead of retrying forever.
        String base = randomName();
        for (int suffix = 2; suffix <= MAX_SUFFIX; suffix++) {
            String candidate = base + " " + suffix;
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("exhausted the name space for base " + base);
    }

    public int combinationCount() {
        return adjectives.size() * nouns.size();
    }

    private String randomName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return adjectives.get(random.nextInt(adjectives.size()))
                + " "
                + nouns.get(random.nextInt(nouns.size()));
    }

    private static List<String> readWords(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> words = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .distinct()
                    .toList();
            if (words.isEmpty()) {
                throw new IllegalStateException("empty word list: " + resource.getDescription());
            }
            return words;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource.getDescription(), e);
        }
    }
}
