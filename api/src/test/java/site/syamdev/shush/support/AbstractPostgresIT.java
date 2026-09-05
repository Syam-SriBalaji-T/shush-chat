package site.syamdev.shush.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Postgres, never a mock or an in-memory substitute.
 *
 * <p>The container is started once in a static initialiser rather than by the JUnit
 * {@code @Testcontainers} extension: that extension stops a static container in each test
 * class's {@code afterAll}, so the second class in a run would get a fresh container on a fresh
 * port while Spring handed it the cached context still pointing at the dead one. Ryuk reaps
 * this one when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    protected TestUsers testUsers;

    @BeforeEach
    void initTestHelpers() {
        this.testUsers = new TestUsers(rest);
    }
}
