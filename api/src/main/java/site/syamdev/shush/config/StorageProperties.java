package site.syamdev.shush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param uploadUrlTtl       how long a presigned PUT stays valid. Short on purpose: the URL is a
 *                           bearer credential for exactly one key, and a long-lived one that
 *                           leaks is an open write endpoint.
 * @param anonymousRetention images in a conversation where neither person has saved their
 *                           account. Storing images costs real money, so the difference between
 *                           this and {@code savedRetention} is genuine rather than an invented
 *                           restriction (pre-plan.md 5, point 4).
 */
@ConfigurationProperties(prefix = "shush.storage")
public record StorageProperties(String endpoint, String region, String bucket,
                                String accessKey, String secretKey,
                                Duration uploadUrlTtl, long maxBytes,
                                Duration anonymousRetention, Duration savedRetention) {
}
