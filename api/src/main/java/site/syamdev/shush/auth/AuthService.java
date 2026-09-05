package site.syamdev.shush.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.user.NameAllocator;
import site.syamdev.shush.user.User;
import site.syamdev.shush.user.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final int TOKEN_BYTES = 32;
    private static final int NAME_ATTEMPTS = 10;

    /** A real bcrypt hash of a value nothing can match, used to keep login timing uniform. */
    private static final String NO_SUCH_USER_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final DeviceTokenRepository deviceTokens;
    private final NameAllocator nameAllocator;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    AuthService(UserRepository users, DeviceTokenRepository deviceTokens,
                NameAllocator nameAllocator, JwtService jwt,
                PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.deviceTokens = deviceTokens;
        this.nameAllocator = nameAllocator;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public Session createAnonymous() {
        Instant now = clock.instant();
        User user = insertWithAllocatedName(now);

        String token = newDeviceToken();
        deviceTokens.save(new DeviceToken(sha256(token), user.getId(), now));

        return new Session(token, jwt.issue(user), user);
    }

    @Transactional
    public Session resumeFromDevice(String token) {
        DeviceToken deviceToken = deviceTokens.findById(sha256(token))
                .orElseThrow(() -> ApiException.unauthorized("unknown_device_token",
                        "this device token is not recognised"));

        Instant now = clock.instant();
        deviceToken.markUsed(now);

        User user = users.findById(deviceToken.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("unknown_user",
                        "this device token points at a user that no longer exists"));
        user.touch(now);

        return new Session(token, jwt.issue(user), user);
    }

    /**
     * Attaches an email and password to the account the caller already has.
     *
     * <p>Nothing is created and nothing is copied: the same row gains an email. That is the
     * entire mechanism behind "everything transfers, nothing resets" (pre-plan.md 8.1), and it
     * is why this is called signup even though the account has existed all along.
     */
    @Transactional
    public Session signUp(UUID userId, String email, String password) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("unknown_user", "no such user"));
        if (!user.isAnonymous()) {
            throw ApiException.badRequest("already_saved", "this account already has an email");
        }

        String normalised = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(normalised)) {
            // Deliberately explicit. Hiding it would be security theatre: anyone can discover
            // the same fact by trying to sign in, and being coy here just breaks the flow.
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "that email is already in use");
        }

        user.attachAccount(normalised, passwordEncoder.encode(password));
        user.touch(clock.instant());
        return new Session(null, jwt.issue(user), user);
    }

    @Transactional
    public Session logIn(String email, String password) {
        User user = users.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
                .orElse(null);

        // Hash even when there is no such user, so a missing email and a wrong password take
        // the same time. Otherwise the difference is a free account-enumeration oracle.
        String storedHash = user == null ? NO_SUCH_USER_HASH : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(password, storedHash);

        if (user == null || !matches) {
            throw ApiException.unauthorized("invalid_credentials", "that email and password do not match");
        }
        user.touch(clock.instant());
        return new Session(null, jwt.issue(user), user);
    }

    /**
     * Forgets this browser. The account itself is untouched -- signing out of an anonymous
     * identity that was never saved is how someone loses it for good, which is exactly the risk
     * pre-plan.md 5 is about.
     */
    @Transactional
    public void signOut(String deviceToken) {
        if (deviceToken != null && !deviceToken.isBlank()) {
            deviceTokens.deleteById(sha256(deviceToken));
        }
    }

    /**
     * The unique index on {@code display_name} is the authority, not the availability check --
     * a check-then-act races with every other allocation happening at the same moment, on this
     * node and on any other.
     */
    private User insertWithAllocatedName(Instant now) {
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String name = nameAllocator.allocate(users::existsByDisplayName);
            UUID id = UUID.randomUUID();
            if (users.insertIfNameFree(id, name, now) == 1) {
                return new User(id, name, now);
            }
            // Someone took it between the check and the insert. The row is untouched and the
            // transaction is still usable, so simply try another name.
        }
        throw new IllegalStateException(
                "could not allocate a display name in " + NAME_ATTEMPTS + " attempts");
    }

    private String newDeviceToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    public record Session(String deviceToken, String jwt, User user) {}
}
