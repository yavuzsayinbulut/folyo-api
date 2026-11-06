package app.folyo.guestbook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GuestbookRepository extends JpaRepository<GuestbookEntry, UUID> {

    @Query("""
            select new app.folyo.guestbook.GuestbookEntryView(e.id, u.name, u.avatarUrl, e.body, e.status, e.createdAt)
            from GuestbookEntry e join User u on u.id = e.authorUserId
            where e.profileId = :profileId and e.status = :status
            order by e.createdAt desc
            """)
    List<GuestbookEntryView> findViews(UUID profileId, GuestbookStatus status, Limit limit);

    @Query("""
            select new app.folyo.guestbook.GuestbookEntryView(e.id, u.name, u.avatarUrl, e.body, e.status, e.createdAt)
            from GuestbookEntry e join User u on u.id = e.authorUserId
            where e.profileId = :profileId
            order by e.createdAt desc
            """)
    List<GuestbookEntryView> findAllViews(UUID profileId, Limit limit);

    Optional<GuestbookEntry> findByIdAndProfileId(UUID id, UUID profileId);

    long countByProfileIdAndAuthorUserIdAndCreatedAtAfter(UUID profileId, UUID authorUserId, Instant after);
}
