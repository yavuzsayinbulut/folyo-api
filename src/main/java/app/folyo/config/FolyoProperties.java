package app.folyo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Uygulama ayarlari. Saglayici bilgileri opsiyoneldir: tanimli olmayan saglayicinin karti
 * "yapilandirilmamis" olarak doner, uygulama yine calisir.
 */
@ConfigurationProperties("folyo")
public record FolyoProperties(
        String webUrl,
        /** OAuth token'larini sifreleyen 32 baytlik AES anahtari, base64. Zorunlu. */
        String tokenKey,
        String mediaDir,
        GitHub github,
        LastFm lastfm,
        OAuthClient reddit,
        OAuthClient spotify,
        OAuthClient strava) {

    public record GitHub(String token) {
    }

    public record LastFm(String apiKey) {
    }

    public record OAuthClient(String clientId, String clientSecret) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public String githubToken() {
        return github == null ? null : github.token();
    }

    public String lastfmApiKey() {
        return lastfm == null ? null : lastfm.apiKey();
    }

    public boolean configured(OAuthClient client) {
        return client != null && client.configured();
    }
}
