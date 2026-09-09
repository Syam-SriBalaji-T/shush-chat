package site.syamdev.shush.cors;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CorsOriginRepository extends JpaRepository<CorsOrigin, String> {
}
