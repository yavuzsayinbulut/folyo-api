package app.folyo.mobile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MobileAuthRepository {

    public record PendingCode(UUID userId, String codeChallenge, String redirectUri, Instant expiresAt) {
    }

    public record ActiveToken(UUID tokenId, UUID userId, String userName, Instant lastUsedAt) {
    }

    private final JdbcClient jdbc;

    public MobileAuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void saveCode(byte[] codeHash, UUID userId, String challenge, String redirectUri, Instant expiresAt) {
        jdbc.sql("""
                insert into mobile_auth_codes (code_hash, user_id, code_challenge, redirect_uri, expires_at)
                values (:hash, :user, :challenge, :redirect, :expires)""")
                .param("hash", codeHash).param("user", userId).param("challenge", challenge)
                .param("redirect", redirectUri).param("expires", java.sql.Timestamp.from(expiresAt)).update();
    }

    /**
     * Kodu tek ifadede siler ve doner. Ayni kodla iki istek ayni anda gelse bile yalniz biri satiri alir;
     * kod ikinci kez kullanilamaz.
     */
    public Optional<PendingCode> consumeCode(byte[] codeHash) {
        return jdbc.sql("""
                delete from mobile_auth_codes where code_hash = :hash
                returning user_id, code_challenge, redirect_uri, expires_at""")
                .param("hash", codeHash)
                .query((rs, i) -> new PendingCode(rs.getObject("user_id", UUID.class), rs.getString("code_challenge"),
                        rs.getString("redirect_uri"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    public void saveToken(UUID id, UUID userId, byte[] tokenHash, String name, Instant expiresAt) {
        jdbc.sql("""
                insert into api_tokens (id, user_id, token_hash, name, expires_at)
                values (:id, :user, :hash, :name, :expires)""")
                .param("id", id).param("user", userId).param("hash", tokenHash).param("name", name)
                .param("expires", java.sql.Timestamp.from(expiresAt)).update();
    }

    public Optional<ActiveToken> findActive(byte[] tokenHash) {
        return jdbc.sql("""
                select t.id, t.user_id, u.name, t.last_used_at from api_tokens t join users u on u.id = t.user_id
                where t.token_hash = :hash and t.revoked_at is null and t.expires_at > now()""")
                .param("hash", tokenHash)
                .query((rs, i) -> new ActiveToken(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("name"),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant()))
                .optional();
    }

    public void touch(UUID tokenId) {
        jdbc.sql("update api_tokens set last_used_at = now() where id = :id").param("id", tokenId).update();
    }

    /** Token iptal edilince o oturumla kaydedilmis cihaz da bildirim almayi birakir. */
    public void revoke(UUID tokenId, UUID userId) {
        jdbc.sql("update api_tokens set revoked_at = now() where id = :id and user_id = :user and revoked_at is null")
                .param("id", tokenId).param("user", userId).update();
        jdbc.sql("delete from device_push_tokens where api_token_id = :id and user_id = :user")
                .param("id", tokenId).param("user", userId).update();
    }

    public void deleteExpiredCodes() {
        jdbc.sql("delete from mobile_auth_codes where expires_at < now()").update();
    }
}
