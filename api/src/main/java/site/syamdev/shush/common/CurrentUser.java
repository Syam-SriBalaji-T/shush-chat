package site.syamdev.shush.common;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves the caller's id from the validated JWT. */
@Component
public class CurrentUser {

    public UUID requireId() {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken token && token.getToken() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        throw ApiException.unauthorized("unauthenticated", "no authenticated user on this request");
    }
}
