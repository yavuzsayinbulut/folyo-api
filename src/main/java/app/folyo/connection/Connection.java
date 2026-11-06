package app.folyo.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "connections")
public class Connection {

    public enum Status {
        ACTIVE,
        REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConnectionProvider provider;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "external_username")
    private String externalUsername;

    @Column(name = "access_token_enc", nullable = false)
    private byte[] accessTokenEnc;

    @Column(name = "refresh_token_enc")
    private byte[] refreshTokenEnc;

    @Column(name = "expires_at")
    private Instant expiresAt;

    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Connection() {
    }

    Connection(UUID profileId, ConnectionProvider provider) {
        this.profileId = profileId;
        this.provider = provider;
        this.status = Status.ACTIVE;
    }

    /** Yeni izin ya da token yenileme sonucunu yazar. Yenilemede refresh token gelmezse eskisi korunur. */
    void apply(TokenGrant grant, byte[] accessTokenEnc, byte[] refreshTokenEnc) {
        this.accessTokenEnc = accessTokenEnc;
        if (refreshTokenEnc != null) {
            this.refreshTokenEnc = refreshTokenEnc;
        }
        this.expiresAt = grant.expiresAt();
        if (grant.scopes() != null) {
            this.scopes = grant.scopes();
        }
        if (grant.externalId() != null) {
            this.externalId = grant.externalId();
        }
        if (grant.externalUsername() != null) {
            this.externalUsername = grant.externalUsername();
        }
        this.status = Status.ACTIVE;
    }

    void revoke() {
        this.status = Status.REVOKED;
    }

    boolean expiresWithin(java.time.Duration margin) {
        return expiresAt != null && Instant.now().plus(margin).isAfter(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public ConnectionProvider getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getExternalUsername() {
        return externalUsername;
    }

    public Status getStatus() {
        return status;
    }

    byte[] getAccessTokenEnc() {
        return accessTokenEnc;
    }

    byte[] getRefreshTokenEnc() {
        return refreshTokenEnc;
    }
}
