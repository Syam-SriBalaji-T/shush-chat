package site.syamdev.shush.realtime;

import java.util.UUID;

final class UserChannels {

    private UserChannels() {
    }

    /** One channel per user. Colon-delimited, per the Redis key convention in CLAUDE.md. */
    static String of(UUID userId) {
        return "user:" + userId;
    }
}
