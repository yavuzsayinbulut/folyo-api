package app.folyo.connection;

import java.time.Instant;

/** Saglayicinin token ucundan donen sonuc; refreshToken yenilemede gelmeyebilir (eskisi gecerli kalir). */
public record TokenGrant(String accessToken, String refreshToken, Instant expiresAt, String scopes,
        String externalId, String externalUsername) {
}
