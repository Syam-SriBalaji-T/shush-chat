package site.syamdev.shush.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final int CLAIM_ATTEMPTS = 10;

    /** Exactly what {@link NameAllocator} produces, with or without a numeric suffix. */
    private static final Pattern GENERATED_NAME = Pattern.compile("[A-Z][a-z]+ [A-Z][a-z]+( \\d+)?");
    private static final Pattern ALLOWED_CUSTOM_NAME =
            Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _.-]{1,22}[\\p{L}\\p{N}]");

    private final UserRepository users;
    private final NameAllocator nameAllocator;
    private final DisplayNameClaimer claimer;
    private final StringRedisTemplate redis;
    private final Duration shuffleInterval;

    UserService(UserRepository users, NameAllocator nameAllocator, DisplayNameClaimer claimer,
                StringRedisTemplate redis,
                @Value("${shush.name.shuffle-interval}") Duration shuffleInterval) {
        this.users = users;
        this.nameAllocator = nameAllocator;
        this.claimer = claimer;
        this.redis = redis;
        this.shuffleInterval = shuffleInterval;
    }

    /**
     * Hands out a fresh generated name and releases the old one.
     *
     * <p>Unlimited shuffling is deliberate (pre-plan.md 8.2) -- it costs one tap and it is the
     * user's own time. The rate limit exists only so a script cannot drain the pool; nobody
     * pressing a button will ever reach it.
     */
    public User shuffleName(UUID userId) {
        Boolean firstInWindow = redis.opsForValue()
                .setIfAbsent("shuffle:" + userId, "1", shuffleInterval);
        if (!Boolean.TRUE.equals(firstInWindow)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "shuffling_too_fast", "wait a moment before shuffling again");
        }

        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            String candidate = nameAllocator.allocate(users::existsByDisplayName);
            if (claimer.claim(userId, candidate)) {
                return require(userId);
            }
        }
        throw new IllegalStateException("could not allocate a display name in "
                + CLAIM_ATTEMPTS + " attempts");
    }

    /**
     * A name you chose is the one thing an assigned name can never be, which is why it is
     * reserved for saved accounts (pre-plan.md 5, point 3).
     */
    public User chooseName(UUID userId, String requested) {
        User user = require(userId);
        if (user.isAnonymous()) {
            throw ApiException.forbidden("account_required",
                    "save your account with an email before choosing your own name");
        }

        String name = requested == null ? "" : requested.trim();
        if (!ALLOWED_CUSTOM_NAME.matcher(name).matches()) {
            throw ApiException.badRequest("invalid_name",
                    "a name is 3 to 24 characters of letters, numbers, spaces, dots, hyphens or underscores");
        }
        if (GENERATED_NAME.matcher(name).matches()) {
            // Otherwise anyone could pick a name indistinguishable from an assigned one and pass
            // themselves off as a stranger who simply never signed up.
            throw ApiException.badRequest("reserved_name_format",
                    "that looks like an assigned name; choose something that is clearly your own");
        }
        if (name.equals(user.getDisplayName())) {
            return user;
        }
        if (!claimer.claim(userId, name)) {
            throw new ApiException(HttpStatus.CONFLICT, "name_taken", "that name is already in use");
        }
        return require(userId);
    }

    @Transactional(readOnly = true)
    public User require(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("unknown_user", "no such user"));
    }
}
