package site.syamdev.shush.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Takes a display name for a user, or reports that someone else got there first.
 *
 * <p>Each attempt is its own transaction on purpose. The {@code not exists} guard loses a race
 * occasionally and the unique index raises -- and in Postgres a raised constraint error aborts
 * the whole transaction, so retrying inside it could never work. Giving each attempt its own
 * transaction is what makes "try another name" possible at all.
 */
@Component
class DisplayNameClaimer {

    private final UserRepository users;

    DisplayNameClaimer(UserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean claim(UUID userId, String name) {
        try {
            return users.claimDisplayName(userId, name) == 1;
        } catch (DataIntegrityViolationException lostTheRace) {
            return false;
        }
    }
}
