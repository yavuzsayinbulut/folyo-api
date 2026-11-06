package app.folyo.card;

import app.folyo.connection.ConnectionProvider;
import app.folyo.connection.ConnectionService;
import app.folyo.media.MediaRepository;
import app.folyo.media.MediaStorage;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CardService {

    static final int MAX_CARDS_PER_PROFILE = 50;

    private static final Pattern GITHUB_USERNAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    private static final Pattern REDDIT_USERNAME = Pattern.compile("^[A-Za-z0-9_-]{3,20}$");
    private static final Pattern LASTFM_USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{1,14}$");

    private final CardRepository cards;
    private final ProfileService profileService;
    private final ConnectionService connectionService;
    private final ApplicationEventPublisher events;
    private final MediaRepository media;
    private final MediaStorage mediaStorage;

    public CardService(CardRepository cards, ProfileService profileService, ConnectionService connectionService,
            ApplicationEventPublisher events, MediaRepository media, MediaStorage mediaStorage) {
        this.cards = cards;
        this.profileService = profileService;
        this.connectionService = connectionService;
        this.events = events;
        this.media = media;
        this.mediaStorage = mediaStorage;
    }

    public record CardChanges(CardSize size, Boolean visible, Map<String, Object> config) {
    }

    @Transactional(readOnly = true)
    public List<Card> listForOwner(UUID userId) {
        return cards.findByProfileIdOrderByPosition(profileService.requireByUserId(userId).getId());
    }

    @Transactional(readOnly = true)
    public List<Card> visibleCards(UUID profileId) {
        return cards.findByProfileIdAndVisibleTrueOrderByPosition(profileId);
    }

    @Transactional
    public Card create(UUID userId, CardType type, CardSize size, Map<String, Object> rawConfig) {
        Profile profile = profileService.lockByUserId(userId);
        if (cards.countByProfileId(profile.getId()) >= MAX_CARDS_PER_PROFILE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Bir sayfada en fazla " + MAX_CARDS_PER_PROFILE + " kart olabilir");
        }
        requireConnectionIfNeeded(profile, type);
        int position = cards.findMaxPosition(profile.getId()) + 1;
        CardSize effectiveSize = size != null ? size : defaultSize(type);
        Card card = cards.save(new Card(profile.getId(), type, position, effectiveSize, normalizeConfig(type, rawConfig)));
        announceIfActivitySource(card);
        return card;
    }

    @Transactional
    public Card update(UUID userId, UUID cardId, CardChanges changes) {
        Card card = requireOwnedCard(userId, cardId);
        if (changes.size() != null) {
            card.resize(changes.size());
        }
        if (changes.visible() != null) {
            card.changeVisibility(changes.visible());
        }
        if (changes.config() != null) {
            card.replaceConfig(normalizeConfig(card.getType(), changes.config()));
        }
        if (changes.visible() != null || changes.config() != null) {
            announceIfActivitySource(card);
        }
        return card;
    }

    @Transactional
    public void delete(UUID userId, UUID cardId) {
        Card card = requireOwnedCard(userId, cardId);
        if (card.getType() == CardType.GALLERY) {
            // Satirlar FK ile silinir; dosyalar yalniz commit basarili olursa silinir.
            List<String> keys = media.findStorageKeys(card.getId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mediaStorage.deleteQuietly(keys);
                }
            });
        }
        cards.delete(card);
        announceIfActivitySource(card);
    }


    /**
     * Sayfadaki butun kartlarin yeni sirasini tek transaction'da yazar. Liste sayfadaki kartlarin
     * tam kumesi olmalidir; eksik, fazla ya da tekrar eden id varsa hicbir sey degismez.
     */
    @Transactional
    public List<Card> reorder(UUID userId, List<UUID> orderedIds) {
        Profile profile = profileService.lockByUserId(userId);
        List<Card> current = cards.findByProfileIdOrderByPosition(profile.getId());

        var requested = new HashSet<>(orderedIds);
        var existing = new HashSet<UUID>();
        current.forEach(card -> existing.add(card.getId()));
        if (requested.size() != orderedIds.size() || !requested.equals(existing)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sıralama sayfadaki kartlarla uyuşmuyor, sayfayı yenile");
        }

        Map<UUID, Card> byId = new LinkedHashMap<>();
        current.forEach(card -> byId.put(card.getId(), card));
        for (int i = 0; i < orderedIds.size(); i++) {
            byId.get(orderedIds.get(i)).moveTo(i);
        }
        return orderedIds.stream().map(byId::get).toList();
    }

    private Card requireOwnedCard(UUID userId, UUID cardId) {
        Profile profile = profileService.lockByUserId(userId);
        // Baskasinin karti da "yok" gibi doner; kartin varligi disari sizdirilmaz.
        return cards.findByIdAndProfileId(cardId, profile.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kart bulunamadı"));
    }

    private void requireConnectionIfNeeded(Profile profile, CardType type) {
        ConnectionProvider provider = switch (type) {
            case SPOTIFY -> ConnectionProvider.SPOTIFY;
            case STRAVA -> ConnectionProvider.STRAVA;
            default -> null;
        };
        if (provider != null && !connectionService.isActive(profile.getId(), provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Önce " + (provider == ConnectionProvider.SPOTIFY ? "Spotify" : "Strava") + " hesabını bağla");
        }
    }

    private void announceIfActivitySource(Card card) {
        if (CardType.ACTIVITY_SOURCES.contains(card.getType())) {
            events.publishEvent(new ActivitySourcesChanged(card.getProfileId()));
        }
    }

    private static CardSize defaultSize(CardType type) {
        return switch (type) {
            case LINK, REDDIT -> CardSize.SMALL;
            case TEXT, GITHUB, LASTFM, SPOTIFY, STRAVA -> CardSize.WIDE;
            case GALLERY -> CardSize.LARGE;
        };
    }

    /** Tipe gore yalniz bilinen alanlari alir, dogrular ve temizlenmis haliyle doner. */
    static Map<String, Object> normalizeConfig(CardType type, Map<String, Object> raw) {
        Map<String, Object> input = raw == null ? Map.of() : raw;
        Map<String, Object> config = new LinkedHashMap<>();
        switch (type) {
            case LINK -> {
                config.put("url", validHttpUrl(text(input, "url")));
                String title = text(input, "title");
                if (title != null) {
                    config.put("title", limit(title, 80, "Başlık en fazla 80 karakter"));
                }
            }
            case TEXT -> {
                String text = text(input, "text");
                if (text == null) {
                    throw badRequest("Kart metnini yaz");
                }
                config.put("text", limit(text, 280, "Metin en fazla 280 karakter"));
            }
            case GITHUB -> config.put("username", username(input, GITHUB_USERNAME, "GitHub"));
            case REDDIT -> config.put("username", username(input, REDDIT_USERNAME, "Reddit"));
            case LASTFM -> config.put("username", username(input, LASTFM_USERNAME, "Last.fm"));
            case SPOTIFY, STRAVA -> {
                // Hesap bilgisi baglantidan gelir; kart ayar tutmaz.
            }
            case GALLERY -> {
                String title = text(input, "title");
                if (title != null) {
                    config.put("title", limit(title, 60, "Başlık en fazla 60 karakter"));
                }
            }
        }
        return config;
    }

    /** "@yavuz", "github.com/yavuz", "u/yavuz" gibi girisleri yalin kullanici adina indirir ve dogrular. */
    private static String username(Map<String, Object> input, Pattern pattern, String platform) {
        String raw = text(input, "username");
        if (raw == null) {
            throw badRequest(platform + " kullanıcı adını yaz");
        }
        String value = raw.replaceAll("/+$", "");
        if (value.contains("/")) {
            value = value.substring(value.lastIndexOf('/') + 1);
        }
        value = value.replaceFirst("^@", "");
        if (!pattern.matcher(value).matches()) {
            throw badRequest("Geçerli bir " + platform + " kullanıcı adı yaz");
        }
        return value;
    }

    private static String validHttpUrl(String value) {
        if (value == null) {
            throw badRequest("Bir link yaz");
        }
        if (value.length() > 2048) {
            throw badRequest("Link çok uzun");
        }
        String candidate = value.contains("://") ? value : "https://" + value;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || !uri.getHost().contains(".")) {
                throw badRequest("Geçerli bir web adresi yaz");
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            throw badRequest("Geçerli bir web adresi yaz");
        }
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static String limit(String value, int max, String message) {
        if (value.length() > max) {
            throw badRequest(message);
        }
        return value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
