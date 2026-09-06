package site.syamdev.shush.auth;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;
import site.syamdev.shush.config.JwtProperties;
import site.syamdev.shush.user.User;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** HS256, 24 h, claims {@code sub} and {@code anon} -- plan.md 3.8. */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;
    private final Clock clock;

    JwtService(JwtEncoder encoder, JwtDecoder decoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim("anon", user.isAnonymous())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /**
     * Used by the WebSocket handshake, which cannot go through the resource-server filter
     * chain because the token arrives in the query string rather than a header (plan.md 3.8).
     */
    public UUID verifyAndExtractUserId(String token) {
        Jwt jwt = decoder.decode(token);
        return UUID.fromString(jwt.getSubject());
    }
}
