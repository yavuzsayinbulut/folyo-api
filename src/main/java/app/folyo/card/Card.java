package app.folyo.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CardType type;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardSize size;

    @Column(name = "is_visible", nullable = false)
    private boolean visible;

    /** Tipe ozel ayarlar. Yazilmadan once CardService.normalizeConfig'ten gecer. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> config;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Card() {
    }

    Card(UUID profileId, CardType type, int position, CardSize size, Map<String, Object> config) {
        this.profileId = profileId;
        this.type = type;
        this.position = position;
        this.size = size;
        this.visible = true;
        this.config = config;
    }

    void moveTo(int position) {
        this.position = position;
    }

    void resize(CardSize size) {
        this.size = size;
    }

    void changeVisibility(boolean visible) {
        this.visible = visible;
    }

    void replaceConfig(Map<String, Object> config) {
        this.config = config;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public CardType getType() {
        return type;
    }

    public int getPosition() {
        return position;
    }

    public CardSize getSize() {
        return size;
    }

    public boolean isVisible() {
        return visible;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
