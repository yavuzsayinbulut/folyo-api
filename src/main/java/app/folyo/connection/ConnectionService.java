package app.folyo.connection;

import app.folyo.config.FolyoProperties;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import app.folyo.provider.AuthorizationRevokedException;
import app.folyo.provider.SpotifyClient;
import app.folyo.provider.StravaClient;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Spotify/Strava hesap baglama (OAuth authorization code) ve token yasam dongusu.
 * Dis HTTP cagrilari (kod takasi) DB transaction'i disinda yapilir; yalniz token yenileme,
 * ayni baglantiyi iki istegin birden yenilememesi icin satir kilidi altinda calisir.
 */
@Service
public class ConnectionService {

    static final String PENDING_ATTRIBUTE = "folyo.connect.pending";
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    record PendingConnect(String state, ConnectionProvider provider, UUID userId) implements Serializable {
    }

    public record ConnectionView(String provider, boolean configured, boolean connected, String externalUsername,
            String status) {
    }

    /** Kilit altindaki yenilemenin sonucu: ya gecerli token ya da iznin geri alindigi bilgisi. */
    private record RefreshOutcome(String accessToken, boolean revoked) {
    }

    private final ConnectionRepository connections;
    private final ProfileService profileService;
    private final TokenCipher cipher;
    private final SpotifyClient spotify;
    private final StravaClient strava;
    private final FolyoProperties properties;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public ConnectionService(ConnectionRepository connections, ProfileService profileService, TokenCipher cipher,
            SpotifyClient spotify, StravaClient strava, FolyoProperties properties, TransactionTemplate transaction) {
        this.connections = connections;
        this.profileService = profileService;
        this.cipher = cipher;
        this.spotify = spotify;
        this.strava = strava;
        this.properties = properties;
        this.transaction = transaction;
    }

    public List<ConnectionView> list(UUID userId) {
        Profile profile = profileService.requireByUserId(userId);
        List<ConnectionView> views = new ArrayList<>();
        for (ConnectionProvider provider : ConnectionProvider.values()) {
            var connection = connections.findByProfileIdAndProvider(profile.getId(), provider);
            views.add(new ConnectionView(provider.slug(), configured(provider),
                    connection.map(c -> c.getStatus() == Connection.Status.ACTIVE).orElse(false),
                    connection.map(Connection::getExternalUsername).orElse(null),
                    connection.map(c -> c.getStatus().name()).orElse(null)));
        }
        return views;
    }

    public boolean isActive(UUID profileId, ConnectionProvider provider) {
        return connections.findByProfileIdAndProvider(profileId, provider)
                .map(c -> c.getStatus() == Connection.Status.ACTIVE).orElse(false);
    }

    /** Saglayicinin izin sayfasinin adresini uretir; tek kullanimlik state oturuma yazilir. */
    public String start(UUID userId, ConnectionProvider provider, HttpSession session) {
        profileService.requireByUserId(userId);
        if (!configured(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bu bağlantı sunucuda yapılandırılmamış");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(PENDING_ATTRIBUTE, new PendingConnect(state, provider, userId));
        return switch (provider) {
            case SPOTIFY -> spotify.authorizeUrl(state, redirectUri(provider));
            case STRAVA -> strava.authorizeUrl(state, redirectUri(provider));
        };
    }

    /**
     * Saglayicidan donusu isler. State tek kullanimliktir ve oturumdaki istekle (saglayici + kullanici) eslesmelidir;
     * eslesmezse baska birinin baslattigi akisla hesap baglanmasi engellenir.
     */
    public void complete(UUID userId, ConnectionProvider provider, String code, String state, HttpSession session) {
        Object attribute = session.getAttribute(PENDING_ATTRIBUTE);
        session.removeAttribute(PENDING_ATTRIBUTE);
        if (!(attribute instanceof PendingConnect pending) || state == null || code == null
                || pending.provider() != provider || !pending.userId().equals(userId)
                || !MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.US_ASCII),
                        state.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bağlantı isteği geçersiz ya da süresi dolmuş");
        }

        TokenGrant grant = switch (provider) {
            case SPOTIFY -> spotify.exchangeCode(code, redirectUri(provider));
            case STRAVA -> strava.exchangeCode(code);
        };
        if (grant.accessToken() == null || grant.externalId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sağlayıcı geçerli bir izin döndürmedi");
        }

        Profile profile = profileService.requireByUserId(userId);
        transaction.executeWithoutResult(status -> {
            Connection connection = connections.findByProfileIdAndProvider(profile.getId(), provider)
                    .orElseGet(() -> new Connection(profile.getId(), provider));
            connection.apply(grant, cipher.encrypt(grant.accessToken()),
                    grant.refreshToken() == null ? null : cipher.encrypt(grant.refreshToken()));
            connections.save(connection);
        });
    }

    public void disconnect(UUID userId, ConnectionProvider provider) {
        Profile profile = profileService.requireByUserId(userId);
        transaction.executeWithoutResult(status -> connections.findByProfileIdAndProvider(profile.getId(), provider)
                .ifPresent(connections::delete));
    }

    /** Gecerli erisim token'ini doner; suresi dolmak uzereyse once yeniler. */
    public String accessToken(UUID profileId, ConnectionProvider provider) {
        Connection connection = connections.findByProfileIdAndProvider(profileId, provider)
                .filter(c -> c.getStatus() == Connection.Status.ACTIVE)
                .orElseThrow(() -> new ConnectionMissingException(provider));
        if (!connection.expiresWithin(REFRESH_MARGIN)) {
            return cipher.decrypt(connection.getAccessTokenEnc());
        }

        RefreshOutcome outcome = transaction.execute(status -> {
            Connection locked = connections.findByIdForUpdate(connection.getId())
                    .orElseThrow(() -> new ConnectionMissingException(provider));
            // Kilidi beklerken baska bir istek yenilemis olabilir; o zaman tekrar yenilenmez.
            if (locked.getStatus() != Connection.Status.ACTIVE) {
                return new RefreshOutcome(null, true);
            }
            if (!locked.expiresWithin(REFRESH_MARGIN)) {
                return new RefreshOutcome(cipher.decrypt(locked.getAccessTokenEnc()), false);
            }
            if (locked.getRefreshTokenEnc() == null) {
                locked.revoke();
                return new RefreshOutcome(null, true);
            }
            try {
                String refreshToken = cipher.decrypt(locked.getRefreshTokenEnc());
                TokenGrant grant = switch (provider) {
                    case SPOTIFY -> spotify.refresh(refreshToken);
                    case STRAVA -> strava.refresh(refreshToken);
                };
                locked.apply(grant, cipher.encrypt(grant.accessToken()),
                        grant.refreshToken() == null ? null : cipher.encrypt(grant.refreshToken()));
                return new RefreshOutcome(grant.accessToken(), false);
            } catch (AuthorizationRevokedException e) {
                // Izin geri alinmis: baglanti REVOKED olarak isaretlenip commit edilir, kart "bagli degil" gosterir.
                locked.revoke();
                return new RefreshOutcome(null, true);
            }
        });
        if (outcome == null || outcome.revoked()) {
            throw new ConnectionMissingException(provider);
        }
        return outcome.accessToken();
    }

    public void markRevoked(UUID profileId, ConnectionProvider provider) {
        transaction.executeWithoutResult(status -> connections.findByProfileIdAndProvider(profileId, provider)
                .ifPresent(Connection::revoke));
    }

    public String externalId(UUID profileId, ConnectionProvider provider) {
        return connections.findByProfileIdAndProvider(profileId, provider).map(Connection::getExternalId)
                .orElse(null);
    }

    public String externalUsername(UUID profileId, ConnectionProvider provider) {
        return connections.findByProfileIdAndProvider(profileId, provider).map(Connection::getExternalUsername)
                .orElse(null);
    }

    public boolean configured(ConnectionProvider provider) {
        return switch (provider) {
            case SPOTIFY -> spotify.configured();
            case STRAVA -> strava.configured();
        };
    }

    String redirectUri(ConnectionProvider provider) {
        return properties.webUrl() + "/api/me/connections/" + provider.slug() + "/callback";
    }
}
