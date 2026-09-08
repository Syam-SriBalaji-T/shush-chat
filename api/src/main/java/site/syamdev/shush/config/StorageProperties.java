package site.syamdev.shush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param publicEndpoint     the endpoint a BROWSER can reach, which is not the one this
 *                           application uses. In Docker the app talks to {@code http://minio:9000}
 *                           over the internal network; a presigned URL built on that host is
 *                           signed for a name no browser can resolve, so the upload fails with
 *                           ERR_NAME_NOT_RESOLVED and nothing server-side ever notices. SigV4
 *                           signs the Host header, so this cannot be rewritten after the fact --
 *                           the URL has to be signed against the host the browser will use.
 * @param uploadUrlTtl       how long a presigned PUT stays valid. Short on purpose: the URL is a
 *                           bearer credential for exactly one key, and a long-lived one that
 *                           leaks is an open write endpoint.
 * @param anonymousRetention images in a conversation where neither person has saved their
 *                           account. Storing images costs real money, so the difference between
 *                           this and {@code savedRetention} is genuine rather than an invented
 *                           restriction (pre-plan.md 5, point 4).
 */
@ConfigurationProperties(prefix = "shush.storage")
public record StorageProperties(String endpoint, String publicEndpoint, String region, String bucket,
                                String accessKey, String secretKey,
                                Duration uploadUrlTtl, long maxBytes,
                                Duration anonymousRetention, Duration savedRetention) {
}
