package app.folyo.notification;

import app.folyo.analytics.AnalyticsRepository;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Cihaz kaydi, bildirim tercihleri ve bildirimlerin kuyruga yazilmasi. Gonderim PushDispatcher'dadir. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern EXPO_TOKEN = Pattern.compile("^Expo(nent)?PushToken\\[[A-Za-z0-9_-]{10,200}]$");

    private final NotificationRepository repository;
    private final ProfileService profileService;
    private final AnalyticsRepository analytics;
    private final ZoneId zone;

    public NotificationService(NotificationRepository repository, ProfileService profileService,
            AnalyticsRepository analytics, @Value("${folyo.analytics.zone:Europe/Istanbul}") String zone) {
        this.repository = repository;
        this.profileService = profileService;
        this.analytics = analytics;
        this.zone = ZoneId.of(zone);
    }

    public void registerDevice(UUID userId, UUID apiTokenId, String expoToken, String platform) {
        if (expoToken == null || !EXPO_TOKEN.matcher(expoToken).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Geçersiz bildirim adresi");
        }
        if (!"ios".equals(platform) && !"android".equals(platform)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Platform ios ya da android olmalı");
        }
        repository.upsertDevice(userId, apiTokenId, expoToken, platform);
    }

    public void unregisterDevice(UUID userId, String expoToken) {
        repository.deleteDeviceOfUser(userId, expoToken);
    }

    public NotificationRepository.Settings settings(UUID userId) {
        return repository.settings(userId);
    }

    public NotificationRepository.Settings saveSettings(UUID userId, NotificationRepository.Settings settings) {
        repository.saveSettings(userId, settings);
        return settings;
    }

    /**
     * Ziyaretci defterine mesaj yazildiginda sayfa sahibine bildirim. Mesajla ayni transaction'da kuyruga girer:
     * mesaj kaydedilemezse bildirim de olusmaz, mesaj kaydedilirse bildirim kaybolmaz.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueGuestbookMessage(Profile profile, UUID authorUserId, String authorName, UUID entryId,
            String body) {
        if (profile.getUserId().equals(authorUserId) || !repository.settings(profile.getUserId()).guestbook()) {
            return;
        }
        String preview = body.length() > 100 ? body.substring(0, 99) + "…" : body;
        repository.enqueue(profile.getUserId(), "guestbook", "Ziyaretçi defterine yeni mesaj",
                authorName + ": " + preview, "{\"path\":\"/\"}", "guestbook:" + entryId);
    }

    /**
     * Pazartesi sabahi gecen haftanin ozeti. Ayni hafta icin kullanici basina tek kayit (dedupe anahtari):
     * is iki kez ya da iki instance'ta calissa da ikinci bildirim olusmaz.
     */
    @Scheduled(cron = "0 0 9 * * MON", zone = "${folyo.analytics.zone:Europe/Istanbul}")
    public void enqueueWeeklySummaries() {
        LocalDate lastMonday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        for (UUID userId : repository.usersForWeeklySummary()) {
            try {
                enqueueWeeklySummary(userId, lastMonday);
            } catch (RuntimeException e) {
                log.warn("Haftalik ozet kuyruga yazilamadi: kullanici {}", userId, e);
            }
        }
    }

    void enqueueWeeklySummary(UUID userId, LocalDate weekStart) {
        Profile profile = profileService.findByUserId(userId).orElse(null);
        if (profile == null) {
            return;
        }
        int visitors = visitors(profile.getId(), weekStart, weekStart.plusDays(6));
        int previous = visitors(profile.getId(), weekStart.minusWeeks(1), weekStart.minusDays(1));
        String change = previous == 0 ? "" : visitors >= previous
                ? " (%" + Math.round((visitors - previous) * 100.0 / previous) + " artış)"
                : " (%" + Math.round((previous - visitors) * 100.0 / previous) + " düşüş)";
        String body = visitors == 0
                ? "Geçen hafta sayfana ziyaret gelmedi. Linkini paylaşmayı dene."
                : "Geçen hafta sayfana " + visitors + " kişi baktı" + change + ".";
        repository.enqueue(userId, "weekly_summary", "Haftalık özet", body, "{\"path\":\"/stats\"}",
                "weekly:" + weekStart + ":" + userId);
    }

    private int visitors(UUID profileId, LocalDate from, LocalDate to) {
        return analytics.views(profileId, from, to).stream().mapToInt(AnalyticsRepository.DayCount::visitors).sum();
    }
}
