package app.folyo.guestbook;

import java.time.Instant;
import java.util.UUID;

public record GuestbookEntryView(UUID id, String authorName, String authorAvatarUrl, String body,
        GuestbookStatus status, Instant createdAt) {
}
