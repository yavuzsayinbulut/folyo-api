package app.folyo.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Yalniz okunur; yazma ActivityEventRepository.upsert ile yapilir. */
@Entity
@Table(name = "activity_events")
public class ActivityEvent {

    @Id
    private UUID id;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String title;

    private String url;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ActivityEvent() {
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
