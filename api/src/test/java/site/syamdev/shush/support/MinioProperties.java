package site.syamdev.shush.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.context.annotation.Bean;

/**
 * Points object storage at the MinIO container. Spring Boot has no service-connection factory
 * for a custom S3 endpoint, so the endpoint and credentials are wired across explicitly rather
 * than left pointing at whatever happens to be on the default port.
 */
@TestConfiguration
public class MinioProperties {

    @Bean
    DynamicPropertyRegistrar minioPropertyRegistrar() {
        return registry -> {
            registry.add("shush.storage.endpoint", AbstractIT.MINIO::getS3URL);
            registry.add("shush.storage.access-key", AbstractIT.MINIO::getUserName);
            registry.add("shush.storage.secret-key", AbstractIT.MINIO::getPassword);
        };
    }
}
