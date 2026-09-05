package site.syamdev.shush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param secret HS256 signing key. Must be at least 32 bytes; there is no default, so a
 *               deployment without SHUSH_JWT_SECRET fails to start rather than signing with
 *               something a reader of this repository already knows.
 */
@ConfigurationProperties(prefix = "shush.jwt")
public record JwtProperties(String secret, Duration ttl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes().length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "shush.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (ttl == null) {
            ttl = Duration.ofHours(24);
        }
    }
}
