package app.folyo.provider;

import java.time.Instant;

/** Aktivite akisina yazilacak tek olay; externalId saglayicidaki kalici kimliktir. */
public record ActivityItem(String externalId, String kind, String title, String url, Instant occurredAt) {
}
