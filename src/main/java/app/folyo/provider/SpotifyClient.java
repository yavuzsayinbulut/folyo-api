package app.folyo.provider;

import app.folyo.config.FolyoProperties;
import app.folyo.connection.TokenGrant;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/** Spotify: kullanicinin izniyle "su an calan" ve son calinan parca. */
@Component
public class SpotifyClient {

    static final String ACCOUNTS = "https://accounts.spotify.com";
    static final String API = "https://api.spotify.com/v1";
    static final String SCOPES = "user-read-currently-playing user-read-recently-played";

    private final RestClient http;
    private final FolyoProperties.OAuthClient client;

    public SpotifyClient(RestClient.Builder builder, FolyoProperties properties) {
        this.http = builder.build();
        this.client = properties.spotify();
    }

    public boolean configured() {
        return client != null && client.configured();
    }

    public String authorizeUrl(String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString(ACCOUNTS + "/authorize")
                .queryParam("client_id", client.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    public TokenGrant exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        JsonNode token = postToken(form);
        String accessToken = Json.text(token, "access_token");
        JsonNode me = call(accessToken, "/me");
        return new TokenGrant(accessToken, Json.text(token, "refresh_token"), expiresAt(token),
                Json.text(token, "scope"), Json.text(me, "id"),
                me.has("display_name") ? Json.text(me, "display_name") : Json.text(me, "id"));
    }

    public TokenGrant refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        JsonNode token = postToken(form);
        return new TokenGrant(Json.text(token, "access_token"), Json.text(token, "refresh_token"), expiresAt(token),
                Json.text(token, "scope"), null, null);
    }

    public LiveData.Track nowPlaying(String accessToken, String profileUrl) {
        JsonNode current = call(accessToken, "/me/player/currently-playing");
        if (current != null && current.path("item").isObject()) {
            return track(current.path("item"), current.path("is_playing").asBoolean(false), null, profileUrl);
        }
        JsonNode recent = call(accessToken, "/me/player/recently-played?limit=1").path("items");
        if (recent.isArray() && !recent.isEmpty()) {
            JsonNode last = recent.get(0);
            String playedAt = Json.text(last, "played_at");
            return track(last.path("track"), false, playedAt == null ? null : Instant.parse(playedAt), profileUrl);
        }
        return new LiveData.Track("SPOTIFY", false, null, null, null, null, null, profileUrl);
    }

    private static LiveData.Track track(JsonNode item, boolean playing, Instant playedAt, String profileUrl) {
        JsonNode artists = item.path("artists");
        String artist = artists.isArray() && !artists.isEmpty() ? Json.text(artists.get(0), "name") : null;
        JsonNode images = item.path("album").path("images");
        String image = images.isArray() && !images.isEmpty() ? Json.text(images.get(0), "url") : null;
        return new LiveData.Track("SPOTIFY", playing, Json.text(item, "name"), artist, image,
                Json.text(item.path("external_urls"), "spotify"), playedAt, profileUrl);
    }

    /** 204 (calan bir sey yok) bos dugum olarak doner. */
    private JsonNode call(String accessToken, String path) {
        try {
            JsonNode body = http.get().uri(API + path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().body(JsonNode.class);
            return body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new AuthorizationRevokedException("Spotify erişim izni geçersiz");
            }
            throw new ProviderUnavailableException("Spotify yanıt vermedi", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Spotify yanıt vermedi", e);
        }
    }

    private JsonNode postToken(MultiValueMap<String, String> form) {
        if (!configured()) {
            throw new ProviderNotConfiguredException("Spotify");
        }
        try {
            return http.post().uri(ACCOUNTS + "/api/token")
                    .headers(h -> h.setBasicAuth(client.clientId(), client.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // invalid_grant: kullanici izni geri almis ya da refresh token gecersiz.
            if (e.getStatusCode().value() == 400) {
                throw new AuthorizationRevokedException("Spotify izni geri alınmış");
            }
            throw new ProviderUnavailableException("Spotify token alınamadı", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Spotify token alınamadı", e);
        }
    }

    private static Instant expiresAt(JsonNode token) {
        long expiresIn = Json.number(token, "expires_in");
        return Instant.now().plusSeconds(expiresIn > 0 ? expiresIn : 3600);
    }
}
