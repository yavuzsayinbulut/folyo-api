package app.folyo.activity;

import app.folyo.card.ActivitySourcesChanged;
import app.folyo.card.Card;
import app.folyo.card.CardRepository;
import app.folyo.card.CardType;
import app.folyo.connection.ConnectionMissingException;
import app.folyo.connection.ConnectionProvider;
import app.folyo.connection.ConnectionService;
import app.folyo.provider.AccountNotFoundException;
import app.folyo.provider.ActivityItem;
import app.folyo.provider.AuthorizationRevokedException;
import app.folyo.provider.GitHubClient;
import app.folyo.provider.ProviderNotConfiguredException;
import app.folyo.provider.ProviderUnavailableException;
import app.folyo.provider.RedditClient;
import app.folyo.provider.StravaClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aktivite akisini saglayicilardan ceker. Dis cagrilar transaction disinda yapilir, sonuclar tek transaction'da yazilir.
 * Yazma idempotenttir (profile_id, provider, external_id upsert); is iki kez ya da iki instance'ta calissa da tekrar olmaz.
 */
@Service
public class ActivitySync {

    private static final Logger log = LoggerFactory.getLogger(ActivitySync.class);
    private static final Duration RETENTION = Duration.ofDays(180);

    private record Fetched(String provider, List<ActivityItem> items) {
    }

    private final CardRepository cards;
    private final ActivityEventRepository events;
    private final ConnectionService connections;
    private final GitHubClient github;
    private final RedditClient reddit;
    private final StravaClient strava;
    private final TransactionTemplate transaction;

    public ActivitySync(CardRepository cards, ActivityEventRepository events, ConnectionService connections,
            GitHubClient github, RedditClient reddit, StravaClient strava, TransactionTemplate transaction) {
        this.cards = cards;
        this.events = events;
        this.connections = connections;
        this.github = github;
        this.reddit = reddit;
        this.strava = strava;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${folyo.activity.sync-interval:PT15M}",
            initialDelayString = "${folyo.activity.sync-initial-delay:PT1M}")
    public void syncAll() {
        for (UUID profileId : cards.findProfileIdsWithVisibleTypes(CardType.ACTIVITY_SOURCES)) {
            try {
                syncProfile(profileId);
            } catch (RuntimeException e) {
                log.warn("Aktivite senkronu basarisiz: profil {}", profileId, e);
            }
        }
    }

    /** Kart eklenip/silindikten sonra commit'i beklemeden ziyaretcinin akisi tazelenir. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSourcesChanged(ActivitySourcesChanged event) {
        try {
            syncProfile(event.profileId());
        } catch (RuntimeException e) {
            log.warn("Aktivite senkronu basarisiz: profil {}", event.profileId(), e);
        }
    }

    public void syncProfile(UUID profileId) {
        List<Card> sources = cards.findByProfileIdAndVisibleTrueAndTypeIn(profileId, CardType.ACTIVITY_SOURCES);
        List<Fetched> fetched = new ArrayList<>();
        // Gecici hatada saglayicinin eski olaylari korunur; hesap yok/baglanti yoksa olaylari silinir.
        Set<String> keep = new HashSet<>();
        for (Card card : sources) {
            String provider = card.getType().name();
            try {
                fetched.add(new Fetched(provider, fetch(profileId, card)));
                keep.add(provider);
            } catch (ProviderUnavailableException e) {
                keep.add(provider);
                log.info("Aktivite alinamadi, eski olaylar korunuyor: {} {}", provider, e.getMessage());
            } catch (ProviderNotConfiguredException | AccountNotFoundException | ConnectionMissingException
                    | AuthorizationRevokedException e) {
                log.debug("Aktivite kaynagi kullanilamiyor: {} {}", provider, e.getMessage());
            }
        }

        transaction.executeWithoutResult(status -> {
            for (Fetched result : fetched) {
                for (ActivityItem item : result.items()) {
                    if (item.externalId() == null || item.occurredAt() == null) {
                        continue;
                    }
                    events.upsert(UUID.randomUUID(), profileId, result.provider(), item.externalId(), item.kind(),
                            truncate(item.title(), 300), item.url(), item.occurredAt());
                }
            }
            if (keep.isEmpty()) {
                events.deleteAllForProfile(profileId);
            } else {
                events.deleteOtherProviders(profileId, keep);
            }
            events.deleteOlderThan(profileId, Instant.now().minus(RETENTION));
        });
    }

    private List<ActivityItem> fetch(UUID profileId, Card card) {
        return switch (card.getType()) {
            case GITHUB -> github.recentActivity(String.valueOf(card.getConfig().get("username")));
            case REDDIT -> reddit.recentActivity(String.valueOf(card.getConfig().get("username")));
            case STRAVA -> strava.recentActivity(connections.accessToken(profileId, ConnectionProvider.STRAVA));
            default -> List.of();
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
