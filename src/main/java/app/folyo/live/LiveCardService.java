package app.folyo.live;

import app.folyo.card.Card;
import app.folyo.card.CardRepository;
import app.folyo.card.CardType;
import app.folyo.connection.ConnectionMissingException;
import app.folyo.connection.ConnectionProvider;
import app.folyo.connection.ConnectionService;
import app.folyo.provider.AccountNotFoundException;
import app.folyo.provider.AuthorizationRevokedException;
import app.folyo.provider.GitHubClient;
import app.folyo.provider.LastFmClient;
import app.folyo.provider.LiveData;
import app.folyo.provider.ProviderNotConfiguredException;
import app.folyo.provider.ProviderUnavailableException;
import app.folyo.provider.RedditClient;
import app.folyo.provider.SpotifyClient;
import app.folyo.provider.StravaClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Canli kart verisini saglayicidan ceker. Her ziyaret saglayiciya gitmesin diye sonuc kisa sure onbellekte tutulur;
 * ayni anahtar icin es zamanli isteklerde yalniz biri saglayiciya gider (Caffeine yukleme kilidi).
 * Saglayici gecici olarak yanit vermezse son basarili veri "STALE" olarak gosterilir.
 */
@Service
public class LiveCardService {

    private static final Logger log = LoggerFactory.getLogger(LiveCardService.class);

    public enum Status {
        OK,
        STALE,
        NOT_CONNECTED,
        NOT_CONFIGURED,
        NOT_FOUND,
        UNAVAILABLE
    }

    public record LiveView(CardType type, Status status, LiveData data) {
    }

    /** Muzik hizli degisir, profil verisi yavas: iki ayri sure. */
    private final Cache<String, LiveData> music = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(20)).maximumSize(10_000).build();
    private final Cache<String, LiveData> profiles = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000).build();
    private final Cache<String, LiveData> lastGood = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24)).maximumSize(20_000).build();

    private final CardRepository cards;
    private final ConnectionService connections;
    private final GitHubClient github;
    private final RedditClient reddit;
    private final LastFmClient lastfm;
    private final SpotifyClient spotify;
    private final StravaClient strava;

    public LiveCardService(CardRepository cards, ConnectionService connections, GitHubClient github,
            RedditClient reddit, LastFmClient lastfm, SpotifyClient spotify, StravaClient strava) {
        this.cards = cards;
        this.connections = connections;
        this.github = github;
        this.reddit = reddit;
        this.lastfm = lastfm;
        this.spotify = spotify;
        this.strava = strava;
    }

    public LiveView forPublicCard(UUID cardId) {
        Card card = cards.findById(cardId)
                .filter(c -> c.isVisible() && c.getType().isLive())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kart bulunamadı"));
        String key = cacheKey(card);
        Cache<String, LiveData> cache = isMusic(card.getType()) ? music : profiles;
        try {
            LiveData data = cache.get(key, k -> fetch(card));
            lastGood.put(key, data);
            return new LiveView(card.getType(), Status.OK, data);
        } catch (ProviderNotConfiguredException e) {
            return new LiveView(card.getType(), Status.NOT_CONFIGURED, null);
        } catch (AccountNotFoundException e) {
            return new LiveView(card.getType(), Status.NOT_FOUND, null);
        } catch (ConnectionMissingException e) {
            return new LiveView(card.getType(), Status.NOT_CONNECTED, null);
        } catch (AuthorizationRevokedException e) {
            connections.markRevoked(card.getProfileId(), connectionProvider(card.getType()));
            return new LiveView(card.getType(), Status.NOT_CONNECTED, null);
        } catch (ProviderUnavailableException e) {
            log.warn("Canli kart verisi alinamadi: {} {}", card.getType(), e.getMessage());
            LiveData previous = lastGood.getIfPresent(key);
            return previous != null
                    ? new LiveView(card.getType(), Status.STALE, previous)
                    : new LiveView(card.getType(), Status.UNAVAILABLE, null);
        }
    }

    private LiveData fetch(Card card) {
        UUID profileId = card.getProfileId();
        return switch (card.getType()) {
            case GITHUB -> github.fetch(username(card));
            case REDDIT -> reddit.fetch(username(card));
            case LASTFM -> lastfm.nowPlaying(username(card));
            case SPOTIFY -> spotify.nowPlaying(connections.accessToken(profileId, ConnectionProvider.SPOTIFY),
                    "https://open.spotify.com/user/" + connections.externalId(profileId, ConnectionProvider.SPOTIFY));
            case STRAVA -> strava.summary(connections.accessToken(profileId, ConnectionProvider.STRAVA),
                    connections.externalId(profileId, ConnectionProvider.STRAVA),
                    connections.externalUsername(profileId, ConnectionProvider.STRAVA));
            default -> throw new IllegalStateException("Canli olmayan kart: " + card.getType());
        };
    }

    /** Ayni GitHub kullanicisini gosteren farkli kartlar ayni onbellek kaydini paylasir. */
    private static String cacheKey(Card card) {
        return switch (card.getType()) {
            case SPOTIFY, STRAVA -> card.getType() + ":" + card.getProfileId();
            default -> card.getType() + ":" + username(card).toLowerCase();
        };
    }

    private static String username(Card card) {
        return String.valueOf(card.getConfig().get("username"));
    }

    private static boolean isMusic(CardType type) {
        return type == CardType.SPOTIFY || type == CardType.LASTFM;
    }

    private static ConnectionProvider connectionProvider(CardType type) {
        return type == CardType.STRAVA ? ConnectionProvider.STRAVA : ConnectionProvider.SPOTIFY;
    }
}
