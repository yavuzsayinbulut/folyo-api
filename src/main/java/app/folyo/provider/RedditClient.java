package app.folyo.provider;

import app.folyo.config.FolyoProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Reddit 2023'ten beri kimliksiz API trafigini engelliyor. Uygulama kimligiyle (client credentials) token alinir;
 * kullanicinin izni gerekmez, herkese acik profil verisi okunur.
 */
@Component
public class RedditClient {

    static final String TOKEN_URL = "https://www.reddit.com/api/v1/access_token";
    static final String API = "https://oauth.reddit.com";

    private record AppToken(String value, Instant expiresAt) {
    }

    private final RestClient http;
    private final FolyoProperties.OAuthClient client;
    private final AtomicReference<AppToken> token = new AtomicReference<>();

    public RedditClient(RestClient.Builder builder, FolyoProperties properties) {
        this.http = builder.build();
        this.client = properties.reddit();
    }

    public boolean configured() {
        return client != null && client.configured();
    }

    public LiveData.Reddit fetch(String username) {
        JsonNode data = getJson("/user/{username}/about", username).path("data");
        if (data.path("is_suspended").asBoolean(false)) {
            throw new AccountNotFoundException("Reddit hesabı askıya alınmış");
        }
        String name = Json.text(data, "name");
        long created = Json.number(data, "created_utc");
        String icon = Json.text(data, "icon_img");
        return new LiveData.Reddit(name, "https://www.reddit.com/user/" + name, Json.number(data, "total_karma"),
                created > 0 ? Instant.ofEpochSecond(created) : null, icon == null ? null : icon.split("\\?")[0]);
    }

    public List<ActivityItem> recentActivity(String username) {
        JsonNode children = getJson("/user/{username}/submitted?limit=10", username).path("data").path("children");
        List<ActivityItem> items = new ArrayList<>();
        for (JsonNode child : children) {
            JsonNode post = child.path("data");
            String title = Json.text(post, "title");
            if (title == null) {
                continue;
            }
            String subreddit = Json.text(post, "subreddit_name_prefixed");
            items.add(new ActivityItem(Json.text(post, "name"), "post",
                    (subreddit != null ? subreddit + ": " : "") + title,
                    "https://www.reddit.com" + Json.text(post, "permalink"),
                    Instant.ofEpochSecond(Json.number(post, "created_utc"))));
        }
        return items;
    }

    private JsonNode getJson(String path, String username) {
        if (!configured()) {
            throw new ProviderNotConfiguredException("Reddit");
        }
        try {
            JsonNode body = http.get().uri(API + path, username)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken())
                    .retrieve().body(JsonNode.class);
            if (body == null) {
                throw new ProviderUnavailableException("Reddit boş yanıt döndü");
            }
            return body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new AccountNotFoundException("Reddit kullanıcısı bulunamadı");
            }
            if (e.getStatusCode().value() == 401) {
                token.set(null);
            }
            throw new ProviderUnavailableException("Reddit yanıt vermedi", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Reddit yanıt vermedi", e);
        }
    }

    /** Uygulama token'i bir saat gecerli; suresi dolmadan yeniden kullanilir. */
    private String appToken() {
        AppToken current = token.get();
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return current.value();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        JsonNode body = http.post().uri(TOKEN_URL)
                .headers(h -> h.setBasicAuth(client.clientId(), client.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class);
        String value = Json.text(body, "access_token");
        if (value == null) {
            throw new ProviderUnavailableException("Reddit token vermedi");
        }
        long expiresIn = Math.max(60, Json.number(body, "expires_in"));
        AppToken fresh = new AppToken(value, Instant.now().plusSeconds(expiresIn - 60));
        token.set(fresh);
        return fresh.value();
    }
}
