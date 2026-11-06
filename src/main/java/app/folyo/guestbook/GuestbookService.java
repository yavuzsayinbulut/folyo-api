package app.folyo.guestbook;

import app.folyo.auth.UserRepository;
import app.folyo.notification.NotificationService;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GuestbookService {

    /** Bir kisi ayni sayfaya saatte en fazla bu kadar mesaj birakabilir; basit spam frenidir. */
    static final int MAX_ENTRIES_PER_HOUR = 5;
    private static final Limit PAGE = Limit.of(50);

    private final GuestbookRepository entries;
    private final ProfileService profileService;
    private final UserRepository users;
    private final NotificationService notifications;

    public GuestbookService(GuestbookRepository entries, ProfileService profileService, UserRepository users,
            NotificationService notifications) {
        this.entries = entries;
        this.profileService = profileService;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<GuestbookEntryView> visibleEntries(String username) {
        Profile profile = profileService.requireByUsername(username);
        return entries.findViews(profile.getId(), GuestbookStatus.VISIBLE, PAGE);
    }

    @Transactional(readOnly = true)
    public List<GuestbookEntryView> entriesForOwner(UUID ownerUserId) {
        return entries.findAllViews(profileService.requireByUserId(ownerUserId).getId(), PAGE);
    }

    @Transactional
    public UUID write(String username, UUID authorUserId, String body) {
        // Bos mesaj @NotBlank ile yakalanir; uzunluk alt siniri burada, tek mesajla.
        if (body.strip().length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mesaj en az 3 karakter olmalı");
        }
        Profile profile = profileService.requireByUsername(username);
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
        if (entries.countByProfileIdAndAuthorUserIdAndCreatedAtAfter(profile.getId(), authorUserId, hourAgo)
                >= MAX_ENTRIES_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Çok sık mesaj gönderdin, biraz sonra tekrar dene");
        }
        GuestbookEntry entry = entries.save(new GuestbookEntry(profile.getId(), authorUserId, body.strip()));
        String authorName = users.findById(authorUserId).map(app.folyo.auth.User::getName).orElse("Bir ziyaretçi");
        notifications.enqueueGuestbookMessage(profile, authorUserId, authorName, entry.getId(), body.strip());
        return entry.getId();
    }

    @Transactional
    public void changeStatus(UUID ownerUserId, UUID entryId, GuestbookStatus status) {
        ownedEntry(ownerUserId, entryId).changeStatus(status);
    }

    @Transactional
    public void delete(UUID ownerUserId, UUID entryId) {
        entries.delete(ownedEntry(ownerUserId, entryId));
    }

    private GuestbookEntry ownedEntry(UUID ownerUserId, UUID entryId) {
        Profile profile = profileService.requireByUserId(ownerUserId);
        return entries.findByIdAndProfileId(entryId, profile.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mesaj bulunamadı"));
    }
}
