package app.folyo.provider;

import app.folyo.config.FolyoProperties;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Last.fm "su an dinliyor": kullanicinin izni gerekmez, yalniz uygulama API anahtari (FOLYO_LASTFM_API_KEY). */
@Component
public class LastFmClient {

    static final String API = "https://ws.audioscrobbler.com/2.0/";

    private final RestClient http;
    private final String apiKey;

    public LastFmClient(RestClient.Builder builder, FolyoProperties properties) {
        this.http = builder.build();
        this.apiKey = properties.lastfmApiKey();
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public LiveData.Track nowPlaying(String username) {
        if (!configured()) {
            throw new ProviderNotConfiguredException("Last.fm");
        }
        JsonNode body;
        try {
            body = http.get()
                    .uri(API + "?method=user.getrecenttracks&user={user}&api_key={key}&format=json&limit=1",
                            username, apiKey)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Last.fm bilinmeyen kullanicida 404 + {"error": 6} doner.
            if (e.getStatusCode().value() == 404) {
                throw new AccountNotFoundException("Last.fm kullanıcısı bulunamadı");
            }
            throw new ProviderUnavailableException("Last.fm yanıt vermedi", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Last.fm yanıt vermedi", e);
        }
        if (body == null || body.has("error")) {
            if (body != null && body.path("error").asInt(0) == 6) {
                throw new AccountNotFoundException("Last.fm kullanıcısı bulunamadı");
            }
            throw new ProviderUnavailableException("Last.fm hata döndü");
        }
        JsonNode tracks = body.path("recenttracks").path("track");
        JsonNode track = tracks.isArray() ? tracks.get(0) : tracks;
        String profileUrl = "https://www.last.fm/user/" + username;
        if (track == null || track.isMissingNode()) {
            return new LiveData.Track("LASTFM", false, null, null, null, null, null, profileUrl);
        }
        boolean nowPlaying = "true".equals(Json.text(track.path("@attr"), "nowplaying"));
        long uts = Json.number(track.path("date"), "uts");
        return new LiveData.Track("LASTFM", nowPlaying, Json.text(track, "name"),
                Json.text(track.path("artist"), "#text"), largestImage(track.path("image")), Json.text(track, "url"),
                uts > 0 ? Instant.ofEpochSecond(uts) : null, profileUrl);
    }

    private static String largestImage(JsonNode images) {
        String url = null;
        for (JsonNode image : images) {
            String candidate = Json.text(image, "#text");
            if (candidate != null) {
                url = candidate;
            }
        }
        return url;
    }
}
