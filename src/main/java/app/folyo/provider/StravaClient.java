package app.folyo.provider;

import app.folyo.config.FolyoProperties;
import app.folyo.connection.TokenGrant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/** Strava: kullanicinin izniyle son aktivite ve haftalik mesafe. */
@Component
public class StravaClient {

    static final String WEB = "https://www.strava.com";
    static final String API = WEB + "/api/v3";
    static final String SCOPES = "read,activity:read";

    private final RestClient http;
    private final FolyoProperties.OAuthClient client;

    public StravaClient(RestClient.Builder builder, FolyoProperties properties) {
        this.http = builder.build();
        this.client = properties.strava();
    }

    public boolean configured() {
        return client != null && client.configured();
    }

    public String authorizeUrl(String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString(WEB + "/oauth/authorize")
                .queryParam("client_id", client.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("approval_prompt", "auto")
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    public TokenGrant exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        JsonNode token = postToken(form);
        JsonNode athlete = token.path("athlete");
        String name = ((firstNonBlank(Json.text(athlete, "firstname"), "") + " "
                + firstNonBlank(Json.text(athlete, "lastname"), "")).strip());
        return new TokenGrant(Json.text(token, "access_token"), Json.text(token, "refresh_token"),
                Instant.ofEpochSecond(Json.number(token, "expires_at")), SCOPES, Json.text(athlete, "id"),
                name.isEmpty() ? Json.text(athlete, "username") : name);
    }

    public TokenGrant refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        JsonNode token = postToken(form);
        return new TokenGrant(Json.text(token, "access_token"), Json.text(token, "refresh_token"),
                Instant.ofEpochSecond(Json.number(token, "expires_at")), null, null, null);
    }

    public LiveData.Strava summary(String accessToken, String athleteId, String athleteName) {
        List<LiveData.Activity> activities = activities(accessToken);
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        double weekDistance = 0;
        int weekCount = 0;
        for (LiveData.Activity activity : activities) {
            if (activity.startDate() != null && activity.startDate().isAfter(weekAgo)) {
                weekDistance += activity.distanceMeters();
                weekCount++;
            }
        }
        return new LiveData.Strava(athleteName, WEB + "/athletes/" + athleteId,
                activities.isEmpty() ? null : activities.getFirst(), weekDistance, weekCount);
    }

    public List<ActivityItem> recentActivity(String accessToken) {
        List<ActivityItem> items = new ArrayList<>();
        for (LiveData.Activity activity : activities(accessToken)) {
            String km = String.format(Locale.forLanguageTag("tr"), "%.1f km", activity.distanceMeters() / 1000);
            String id = activity.url().substring(activity.url().lastIndexOf('/') + 1);
            items.add(new ActivityItem(id, "workout", km + " " + sportName(activity.sportType()) + " · " + activity.name(),
                    activity.url(), activity.startDate()));
        }
        return items;
    }

    static String sportName(String sportType) {
        if (sportType == null) {
            return "aktivite";
        }
        return switch (sportType) {
            case "Run", "TrailRun", "VirtualRun" -> "koşu";
            case "Ride", "VirtualRide", "MountainBikeRide", "GravelRide", "EBikeRide" -> "bisiklet";
            case "Walk" -> "yürüyüş";
            case "Hike" -> "doğa yürüyüşü";
            case "Swim" -> "yüzme";
            default -> sportType.toLowerCase(Locale.ROOT);
        };
    }

    private List<LiveData.Activity> activities(String accessToken) {
        JsonNode list;
        try {
            list = http.get().uri(API + "/athlete/activities?per_page=20")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new AuthorizationRevokedException("Strava erişim izni geçersiz");
            }
            throw new ProviderUnavailableException("Strava yanıt vermedi", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Strava yanıt vermedi", e);
        }
        List<LiveData.Activity> activities = new ArrayList<>();
        if (list == null) {
            return activities;
        }
        for (JsonNode a : list) {
            String start = Json.text(a, "start_date");
            activities.add(new LiveData.Activity(Json.text(a, "name"),
                    firstNonBlank(Json.text(a, "sport_type"), Json.text(a, "type")),
                    a.path("distance").asDouble(0), Json.number(a, "moving_time"),
                    start == null ? null : Instant.parse(start), WEB + "/activities/" + Json.text(a, "id")));
        }
        return activities;
    }

    private JsonNode postToken(MultiValueMap<String, String> form) {
        if (!configured()) {
            throw new ProviderNotConfiguredException("Strava");
        }
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        try {
            return http.post().uri(WEB + "/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401) {
                throw new AuthorizationRevokedException("Strava izni geri alınmış");
            }
            throw new ProviderUnavailableException("Strava token alınamadı", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Strava token alınamadı", e);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
