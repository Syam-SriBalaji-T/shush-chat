package site.syamdev.shush.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InterestService {

    /** Five tiles, because step 2 of the journey is one screen with five of them. */
    static final int DEFAULT_TILE_COUNT = 5;

    private final InterestRepository interests;
    private final UserInterestRepository userInterests;
    private final Clock clock;

    InterestService(InterestRepository interests, UserInterestRepository userInterests, Clock clock) {
        this.interests = interests;
        this.userInterests = userInterests;
        this.clock = clock;
    }

    /**
     * A first-time visitor sees the five most popular interests; a returning one sees their
     * own, already selected (pre-plan.md 3, step 2).
     */
    @Transactional(readOnly = true)
    public Suggestions suggestFor(UUID userId) {
        List<Interest> all = interests.findAllByOrderByPopularityDescIdAsc();

        List<Interest> suggested = userId == null ? List.of() : interests.findLastUsedBy(userId);
        boolean returning = !suggested.isEmpty();
        if (!returning) {
            suggested = all.stream().limit(DEFAULT_TILE_COUNT).toList();
        }

        return new Suggestions(suggested.stream().limit(DEFAULT_TILE_COUNT).toList(), all, returning);
    }

    @Transactional
    public void recordSelection(UUID userId, List<Short> interestIds) {
        Instant now = clock.instant();
        userInterests.deleteAllInBatch(userInterests.findByUserId(userId));
        userInterests.saveAll(interestIds.stream()
                .distinct()
                .map(id -> new UserInterest(userId, id, now))
                .toList());
    }

    public record Suggestions(List<Interest> suggested, List<Interest> all, boolean fromHistory) {}
}
