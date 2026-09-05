package site.syamdev.shush.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Real Postgres, real Redis, a real broker. Nothing here is mocked, and the broker in
 * particular never will be: partition assignment is exactly the behaviour a mock removes, and
 * it is the behaviour the whole ordering claim rests on.
 *
 * <p>Containers are started in a static initialiser rather than by the JUnit
 * {@code @Testcontainers} extension: that extension stops a static container in each test
 * class's {@code afterAll}, so the second class in a run would get fresh containers on fresh
 * ports while Spring handed it the cached context still pointing at the dead ones. Ryuk reaps
 * these when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIT {

    @ServiceConnection
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine");

    @ServiceConnection(name = "redis")
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4.7-alpine").withExposedPorts(6379);

    @ServiceConnection
    public static final RedpandaContainer REDPANDA =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v25.3.17");

    @ServiceConnection
    public static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.19.7")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        POSTGRES.start();
        REDIS.start();
        REDPANDA.start();
        ELASTICSEARCH.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    protected TestUsers testUsers;

    @BeforeEach
    void initTestHelpers() {
        this.testUsers = new TestUsers(rest);
    }
}
