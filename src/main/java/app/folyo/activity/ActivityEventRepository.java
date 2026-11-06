package app.folyo.activity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {

    /** Ayni olay tekrar gelirse yeni satir acilmaz, yalniz basligi guncellenir. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into activity_events (id, profile_id, provider, external_id, kind, title, url, occurred_at)
            values (:id, :profileId, :provider, :externalId, :kind, :title, :url, :occurredAt)
            on conflict (profile_id, provider, external_id)
            do update set title = excluded.title, url = excluded.url
            """)
    void upsert(UUID id, UUID profileId, String provider, String externalId, String kind, String title, String url,
            Instant occurredAt);

    @Modifying
    @Query("delete from ActivityEvent e where e.profileId = :profileId and e.provider not in :providers")
    void deleteOtherProviders(UUID profileId, Collection<String> providers);

    @Modifying
    @Query("delete from ActivityEvent e where e.profileId = :profileId")
    void deleteAllForProfile(UUID profileId);

    @Modifying
    @Query("delete from ActivityEvent e where e.profileId = :profileId and e.occurredAt < :before")
    void deleteOlderThan(UUID profileId, Instant before);

    List<ActivityEvent> findTop12ByProfileIdAndProviderInOrderByOccurredAtDesc(UUID profileId,
            Collection<String> providers);
}
