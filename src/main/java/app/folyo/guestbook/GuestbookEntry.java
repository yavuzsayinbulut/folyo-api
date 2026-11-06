package app.folyo.guestbook;

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

@Entity
@Table(name = "guestbook_entries")
public class GuestbookEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(nullable = false, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuestbookStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GuestbookEntry() {
    }

    GuestbookEntry(UUID profileId, UUID authorUserId, String body) {
        this.profileId = profileId;
        this.authorUserId = authorUserId;
        this.body = body;
        this.status = GuestbookStatus.VISIBLE;
    }

    void changeStatus(GuestbookStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }
}
