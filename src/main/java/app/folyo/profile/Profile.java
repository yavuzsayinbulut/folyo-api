package app.folyo.profile;

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
@Table(name = "profiles")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String bio;
    private String location;
    private String timezone;
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProfileVisibility visibility;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() {
    }

    public Profile(UUID userId, String username, String displayName) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.visibility = ProfileVisibility.PUBLIC;
    }

    public void updateDetails(String displayName, String bio, String location, String timezone, String status,
            ProfileVisibility visibility) {
        this.displayName = displayName;
        this.bio = bio;
        this.location = location;
        this.timezone = timezone;
        this.status = status;
        this.visibility = visibility;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getLocation() {
        return location;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getStatus() {
        return status;
    }

    public ProfileVisibility getVisibility() {
        return visibility;
    }
}
