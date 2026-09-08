package site.syamdev.shush.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * One S3 client, pointed wherever the endpoint says.
 *
 * <p>The application only ever speaks the S3 API. Locally the endpoint is MinIO, so a reviewer
 * needs no AWS account; deployed it is real S3. That the storage layer is abstracted behind an
 * API rather than a vendor's SDK is the whole reason the same code runs in both places.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class ObjectStorageConfig {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                // MinIO serves buckets as a path, not a subdomain. Virtual-host style would
                // resolve to a hostname that does not exist locally.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * Signs URLs for the browser, not for this application, so it is built on the public
     * endpoint rather than the internal one. Those differ everywhere the app and the browser
     * are not on the same network -- which is every containerised deployment.
     */
    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.publicEndpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

}
