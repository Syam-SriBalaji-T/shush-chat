package site.syamdev.shush.auth;

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
import java.util.UUID;

@Service
public class AuthService {

    private static final int TOKEN_BYTES = 32;
    private static final int NAME_ATTEMPTS = 10;

    private final UserRepository users;
    private final DeviceTokenRepository deviceTokens;
    private final NameAllocator nameAllocator;
    private final JwtService jwt;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    AuthService(UserRepository users, DeviceTokenRepository deviceTokens,
                NameAllocator nameAllocator, JwtService jwt, Clock clock) {
        this.users = users;
        this.deviceTokens = deviceTokens;
        this.nameAllocator = nameAllocator;
        this.jwt = jwt;
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
