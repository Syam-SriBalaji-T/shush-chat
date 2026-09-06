package site.syamdev.shush.user;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NameAllocatorTest {

    private final NameAllocator allocator = new NameAllocator(
            new ClassPathResource("names/adjectives.txt"),
            new ClassPathResource("names/nouns.txt"));

    @Test
    void generatesTwoWordNamesFromTheWordLists() {
        String name = allocator.allocate(candidate -> false);

        assertThat(name).matches("[A-Z][a-z]+ [A-Z][a-z]+");
    }

    @Test
    void bothListsAreTwoHundredWordsSoTheSpaceIsFortyThousand() {
        assertThat(allocator.combinationCount()).isEqualTo(40_000);
    }

    @Test
    void skipsNamesThatAreAlreadyTaken() {
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String name = allocator.allocate(taken::contains);
            assertThat(taken).doesNotContain(name);
            taken.add(name);
        }
    }

    @Test
    void fallsBackToANumericSuffixWhenTheRandomSpaceKeepsColliding() {
        // Everything unsuffixed is taken, so the only way out is the suffix walk.
        String name = allocator.allocate(candidate -> !candidate.matches(".* \\d+$"));

        assertThat(name).matches("[A-Z][a-z]+ [A-Z][a-z]+ \\d+");
    }
}
