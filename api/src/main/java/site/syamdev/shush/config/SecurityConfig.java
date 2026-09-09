package site.syamdev.shush.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import site.syamdev.shush.cors.AllowedOrigins;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.DispatcherType;

import javax.crypto.SecretKey;

import java.time.Duration;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfig {

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Accepts the JWT in a {@code token} query parameter, but only for reading media.
     *
     * <p>An {@code <img src>} cannot set an Authorization header -- the same constraint that
     * puts the token in the query string for the WebSocket handshake. Without this every image
     * in the client renders as a broken icon behind a 401, which is exactly what happened: the
     * upload worked, the message arrived, and the picture never appeared.
     *
     * <p>Scoped to this one path rather than switched on globally. A token in a URL ends up in
     * access logs, {@code Referer} headers and browser history, so it is worth exactly one
     * endpoint that cannot work any other way -- and that endpoint returns a redirect to a
     * short-lived storage URL rather than any bytes of its own.
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver fromHeader = new DefaultBearerTokenResolver();
        return request -> {
            String header = fromHeader.resolve(request);
            if (header != null) {
                return header;
            }
            // Named "token" to match the websocket handshake, which already carries the JWT
            // this way for the same reason. Spring's own query-parameter support is not reused
            // because it reads RFC 6750's "access_token", and one name for one thing across
            // this client is worth more than matching a spec nothing else here follows.
            if (HttpMethod.GET.matches(request.getMethod())
                    && request.getRequestURI().startsWith("/api/media/")) {
                String token = request.getParameter("token");
                return token == null || token.isBlank() ? null : token;
            }
            return null;
        };
    }

    /**
     * CORS decided per request from the {@code cors_origins} table, so adding a frontend is a
     * row rather than a redeploy.
     *
     * <p>Returning null for an origin that is not listed is how this refuses: Spring's CORS
     * filter then rejects the request instead of answering it without the header, which is the
     * difference between a clear 403 and a browser error nobody can explain.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AllowedOrigins allowed) {
        return request -> {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (!allowed.permits(origin)) {
                return null;
            }
            CorsConfiguration configuration = new CorsConfiguration();
            // The exact origin, never "*": credentials are not usable with a wildcard, and
            // echoing only what was matched keeps the answer specific to the caller.
            configuration.setAllowedOrigins(List.of(origin));
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            configuration.setMaxAge(Duration.ofMinutes(10));
            return configuration;
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // No argument: Spring Security picks up the bean named corsConfigurationSource
                // above. Injecting it here instead is ambiguous, because Spring MVC's
                // HandlerMappingIntrospector is also a CorsConfigurationSource.
                .cors(Customizer.withDefaults())
                // No cookies and no server-side session: every node must be able to serve
                // every request, which is the same property that removes sticky sessions.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Without this the filter chain also guards the ERROR dispatch, so every
                        // 404 and 500 comes back as a 401 -- which sends anyone debugging a
                        // missing endpoint looking at authentication instead.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**", "/api/health", "/api/health/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/interests").permitAll()
                        // The handshake authenticates itself: the JWT arrives in the query
                        // string, which no Authorization-header filter can read.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
