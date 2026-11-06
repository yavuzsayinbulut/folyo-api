package app.folyo.analytics;

import app.folyo.card.Card;
import app.folyo.card.CardRepository;
import app.folyo.card.CardSize;
import app.folyo.card.CardType;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sayfa goruntulenme ve kart tiklama istatistigi. Sayilmayanlar: sayfanin sahibi, botlar/link onizleyicileri.
 * Gun sinirlari tek bir platform saat diliminde hesaplanir (folyo.analytics.zone).
 */
@Service
public class AnalyticsService {

    static final int RETENTION_DAYS = 400;

    /** Link onizleyicileri (WhatsApp, Slack...) ve tarayici olmayan istemciler sayilmaz. */
    private static final Pattern BOT = Pattern.compile(
            "(?i)(bot|crawl|spider|slurp|preview|facebookexternalhit|embedly|whatsapp|telegram|discord|headless"
                    + "|lighthouse|curl|wget|python|java/|okhttp|go-http-client|axios|node-fetch)");

    public record Visitor(String ip, String userAgent, UUID viewerUserId) {
    }

    public record DayStat(LocalDate day, int views, int visitors) {
    }

    public record Totals(int views, int visitors, int clicks) {
    }

    public record TopCard(UUID cardId, CardType type, CardSize size, Map<String, Object> config, int clicks) {
    }

    public record StatsView(int days, List<DayStat> series, Totals totals, Totals previous, List<TopCard> topCards) {
    }

    private final AnalyticsRepository repository;
    private final VisitorHasher hasher;
    private final ProfileService profileService;
    private final CardRepository cards;
    private final TransactionTemplate transaction;
    private final ZoneId zone;

    public AnalyticsService(AnalyticsRepository repository, VisitorHasher hasher, ProfileService profileService,
            CardRepository cards, TransactionTemplate transaction,
            @Value("${folyo.analytics.zone:Europe/Istanbul}") String zone) {
        this.repository = repository;
        this.hasher = hasher;
        this.profileService = profileService;
        this.cards = cards;
        this.transaction = transaction;
        this.zone = ZoneId.of(zone);
    }

    public void recordView(String username, Visitor visitor) {
        Profile profile = profileService.requireByUsername(username);
        if (!countable(visitor, profile)) {
            return;
        }
        LocalDate today = LocalDate.now(zone);
        byte[] hash = hasher.hash(today, visitor.ip(), visitor.userAgent());
        transaction.executeWithoutResult(status -> {
            boolean newVisitor = repository.markFirstVisit(profile.getId(), today, hash);
            repository.addView(profile.getId(), today, newVisitor);
        });
    }

    /** Ayni ziyaretcinin ayni gun ayni karta tekrar tiklamasi sayilmaz. Gizli/olmayan kart sessizce yok sayilir. */
    public void recordClick(UUID cardId, Visitor visitor) {
        Optional<Card> card = cards.findById(cardId).filter(Card::isVisible);
        if (card.isEmpty()) {
            return;
        }
        UUID cardProfileId = card.get().getProfileId();
        boolean ownCard = visitor.viewerUserId() != null && profileService.findByUserId(visitor.viewerUserId())
                .map(viewerProfile -> viewerProfile.getId().equals(cardProfileId)).orElse(false);
        if (isBot(visitor) || ownCard) {
            return;
        }
        LocalDate today = LocalDate.now(zone);
        byte[] hash = hasher.hash(today, visitor.ip(), visitor.userAgent());
        transaction.executeWithoutResult(status -> {
            if (repository.markFirstVisit(cardId, today, hash)) {
                repository.addClick(cardId, today);
            }
        });
    }

    public StatsView stats(UUID userId, int days) {
        if (days != 7 && days != 30 && days != 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dönem 7, 30 ya da 90 gün olabilir");
        }
        Profile profile = profileService.requireByUserId(userId);
        LocalDate to = LocalDate.now(zone);
        LocalDate from = to.minusDays(days - 1L);
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1L);

        Map<LocalDate, AnalyticsRepository.DayCount> byDay = new HashMap<>();
        repository.views(profile.getId(), from, to).forEach(d -> byDay.put(d.day(), d));
        List<DayStat> series = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            var count = byDay.get(day);
            series.add(new DayStat(day, count == null ? 0 : count.views(), count == null ? 0 : count.visitors()));
        }

        List<AnalyticsRepository.CardCount> clicks = repository.clicksByCard(profile.getId(), from, to);
        Map<UUID, Card> cardsById = new HashMap<>();
        cards.findByProfileIdOrderByPosition(profile.getId()).forEach(c -> cardsById.put(c.getId(), c));
        List<TopCard> top = clicks.stream()
                .filter(c -> cardsById.containsKey(c.cardId()))
                .limit(5)
                .map(c -> {
                    Card card = cardsById.get(c.cardId());
                    return new TopCard(card.getId(), card.getType(), card.getSize(), card.getConfig(), c.clicks());
                })
                .toList();

        return new StatsView(days, series, totals(series, clicks),
                totals(profile.getId(), previousFrom, previousTo), top);
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "${folyo.analytics.zone:Europe/Istanbul}")
    public void dailyCleanup() {
        LocalDate today = LocalDate.now(zone);
        repository.forgetVisitorsBefore(today.minusDays(1));
        repository.deleteCountsBefore(today.minusDays(RETENTION_DAYS));
    }

    private Totals totals(UUID profileId, LocalDate from, LocalDate to) {
        List<AnalyticsRepository.DayCount> views = repository.views(profileId, from, to);
        int clicks = repository.clicksByCard(profileId, from, to).stream().mapToInt(AnalyticsRepository.CardCount::clicks)
                .sum();
        return new Totals(views.stream().mapToInt(AnalyticsRepository.DayCount::views).sum(),
                views.stream().mapToInt(AnalyticsRepository.DayCount::visitors).sum(), clicks);
    }

    private static Totals totals(List<DayStat> series, List<AnalyticsRepository.CardCount> clicks) {
        return new Totals(series.stream().mapToInt(DayStat::views).sum(),
                series.stream().mapToInt(DayStat::visitors).sum(),
                clicks.stream().mapToInt(AnalyticsRepository.CardCount::clicks).sum());
    }

    private boolean countable(Visitor visitor, Profile profile) {
        return !isBot(visitor) && !profile.getUserId().equals(visitor.viewerUserId());
    }

    private static boolean isBot(Visitor visitor) {
        String ua = visitor.userAgent();
        return ua == null || ua.isBlank() || BOT.matcher(ua).find();
    }
}
