package app.folyo.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import app.folyo.config.FolyoProperties;
import app.folyo.connection.TokenGrant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Saglayici yanitlarinin okunmasi; gercek ag yerine sahte HTTP sunucusu kullanilir. */
class ProviderClientsTest {

    private static final FolyoProperties.OAuthClient CLIENT = new FolyoProperties.OAuthClient("id", "secret");
    private static final FolyoProperties PROPS = new FolyoProperties("http://127.0.0.1:3100", null, "/tmp",
            new FolyoProperties.GitHub(null), new FolyoProperties.LastFm("lfm-key"), CLIENT, CLIENT, CLIENT);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

    @Test
    void gitHubProfileReposHeatmapAndEvents() {
        server.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withSuccess("""
                        {"login":"octocat","name":"The Octocat","avatar_url":"https://a/o.png",
                         "html_url":"https://github.com/octocat","followers":42,"public_repos":8}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/users/octocat/repos?sort=pushed&per_page=100"))
                .andRespond(withSuccess("""
                        [{"name":"fork","fork":true,"stargazers_count":999,"html_url":"u0"},
                         {"name":"small","fork":false,"stargazers_count":3,"html_url":"u1","language":"Go"},
                         {"name":"big","fork":false,"stargazers_count":120,"html_url":"u2","language":"Java"}]""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://github.com/users/octocat/contributions"))
                .andRespond(withSuccess(contributionsHtml(LocalDate.of(2026, 11, 6), 400), MediaType.TEXT_HTML));

        LiveData.GitHub data = new GitHubClient(builder, PROPS).fetch("octocat");

        assertThat(data.followers()).isEqualTo(42);
        assertThat(data.topRepos()).extracting(LiveData.Repo::name).containsExactly("big", "small");
        // 26 tam hafta + icinde bulunulan hafta, pazardan baslar.
        assertThat(data.contributionLevels()).hasSizeBetween(GitHubClient.HEATMAP_WEEKS * 7 - 6,
                GitHubClient.HEATMAP_WEEKS * 7 + 7);
        assertThat(data.contributionLevels().size() % 7).isEqualTo(LocalDate.of(2026, 11, 6).getDayOfWeek().getValue() % 7 + 1);
        assertThat(data.activeDays()).isPositive();
        server.verify();
    }

    @Test
    void gitHubEventsBecomeActivityAndUnknownUserIsNotFound() {
        server.expect(requestTo("https://api.github.com/users/octocat/events/public?per_page=30"))
                .andRespond(withSuccess("""
                        [{"id":"1","type":"PushEvent","repo":{"name":"octocat/hello"},
                          "payload":{"ref":"refs/heads/main"},"created_at":"2026-11-05T10:00:00Z"},
                         {"id":"2","type":"IssueCommentEvent","repo":{"name":"octocat/hello"},"payload":{},
                          "created_at":"2026-11-05T09:00:00Z"},
                         {"id":"3","type":"WatchEvent","repo":{"name":"spring/boot"},"payload":{},
                          "created_at":"2026-11-04T09:00:00Z"}]""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/users/yok-boyle/events/public?per_page=30"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        GitHubClient client = new GitHubClient(builder, PROPS);
        List<ActivityItem> items = client.recentActivity("octocat");

        assertThat(items).extracting(ActivityItem::title)
                .containsExactly("octocat/hello reposuna push (main)", "spring/boot reposuna yıldız verdi");
        assertThatThrownBy(() -> client.recentActivity("yok-boyle")).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void lastFmNowPlayingAndMissingUser() {
        server.expect(requestTo("https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user=rj&api_key=lfm-key&format=json&limit=1"))
                .andRespond(withSuccess("""
                        {"recenttracks":{"track":[{"name":"Gece","artist":{"#text":"Sanatçı"},"url":"https://l/t",
                          "image":[{"#text":"s.jpg","size":"small"},{"#text":"xl.jpg","size":"extralarge"}],
                          "@attr":{"nowplaying":"true"}}]}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user=nobody&api_key=lfm-key&format=json&limit=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":6,\"message\":\"User not found\"}"));

        LastFmClient client = new LastFmClient(builder, PROPS);
        LiveData.Track track = client.nowPlaying("rj");
        assertThat(track.nowPlaying()).isTrue();
        assertThat(track.artist()).isEqualTo("Sanatçı");
        assertThat(track.imageUrl()).isEqualTo("xl.jpg");
        assertThatThrownBy(() -> client.nowPlaying("nobody")).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void spotifyCodeExchangeAndRecentlyPlayedFallback() {
        server.expect(requestTo("https://accounts.spotify.com/api/token")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic aWQ6c2VjcmV0"))
                .andExpect(content().formDataContains(java.util.Map.of("grant_type", "authorization_code", "code", "abc")))
                .andRespond(withSuccess("""
                        {"access_token":"AT","refresh_token":"RT","expires_in":3600,
                         "scope":"user-read-currently-playing"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.spotify.com/v1/me")).andExpect(header("Authorization", "Bearer AT"))
                .andRespond(withSuccess("{\"id\":\"sp-user\",\"display_name\":\"Yavuz\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.spotify.com/v1/me/player/currently-playing")).andRespond(withNoContent());
        server.expect(requestTo("https://api.spotify.com/v1/me/player/recently-played?limit=1"))
                .andRespond(withSuccess("""
                        {"items":[{"played_at":"2026-11-06T08:00:00Z","track":{"name":"Şarkı",
                          "artists":[{"name":"Grup"}],"album":{"images":[{"url":"cover.jpg"}]},
                          "external_urls":{"spotify":"https://open.spotify.com/track/1"}}}]}""",
                        MediaType.APPLICATION_JSON));

        SpotifyClient client = new SpotifyClient(builder, PROPS);
        TokenGrant grant = client.exchangeCode("abc", "http://127.0.0.1:3100/cb");
        assertThat(grant.externalId()).isEqualTo("sp-user");
        assertThat(grant.refreshToken()).isEqualTo("RT");

        LiveData.Track track = client.nowPlaying("AT", "https://open.spotify.com/user/sp-user");
        assertThat(track.nowPlaying()).isFalse();
        assertThat(track.title()).isEqualTo("Şarkı");
        assertThat(track.playedAt()).isNotNull();
        server.verify();
    }

    @Test
    void spotifyInvalidGrantMeansRevoked() {
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));
        assertThatThrownBy(() -> new SpotifyClient(builder, PROPS).refresh("old"))
                .isInstanceOf(AuthorizationRevokedException.class);
    }

    @Test
    void stravaExchangeAndWeeklySummary() {
        server.expect(requestTo("https://www.strava.com/oauth/token"))
                .andExpect(content().formDataContains(java.util.Map.of("client_id", "id", "code", "c1")))
                .andRespond(withSuccess("""
                        {"access_token":"SAT","refresh_token":"SRT","expires_at":4102444800,
                         "athlete":{"id":77,"firstname":"Yavuz","lastname":"S."}}""", MediaType.APPLICATION_JSON));
        String recent = java.time.Instant.now().minusSeconds(86_400).toString();
        server.expect(requestTo("https://www.strava.com/api/v3/athlete/activities?per_page=20"))
                .andRespond(withSuccess("""
                        [{"id":9,"name":"Sabah koşusu","sport_type":"Run","distance":10200.0,"moving_time":3200,
                          "start_date":"%s"},
                         {"id":8,"name":"Eski","sport_type":"Ride","distance":30000.0,"moving_time":4000,
                          "start_date":"2020-01-01T00:00:00Z"}]""".formatted(recent), MediaType.APPLICATION_JSON));

        StravaClient client = new StravaClient(builder, PROPS);
        TokenGrant grant = client.exchangeCode("c1");
        assertThat(grant.externalId()).isEqualTo("77");
        assertThat(grant.externalUsername()).isEqualTo("Yavuz S.");

        LiveData.Strava summary = client.summary("SAT", "77", "Yavuz S.");
        assertThat(summary.weekActivities()).isEqualTo(1);
        assertThat(summary.weekDistanceMeters()).isEqualTo(10200.0);
        assertThat(summary.lastActivity().name()).isEqualTo("Sabah koşusu");
        server.verify();
    }

    @Test
    void redditUsesAppTokenOnce() {
        server.expect(requestTo("https://www.reddit.com/api/v1/access_token"))
                .andRespond(withSuccess("{\"access_token\":\"APP\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oauth.reddit.com/user/spez/about"))
                .andExpect(header("Authorization", "Bearer APP"))
                .andRespond(withSuccess("""
                        {"data":{"name":"spez","total_karma":1234,"created_utc":1118030400,
                          "icon_img":"https://i/x.png?size=256"}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oauth.reddit.com/user/spez/submitted?limit=10"))
                .andExpect(header("Authorization", "Bearer APP"))
                .andRespond(withSuccess("""
                        {"data":{"children":[{"data":{"name":"t3_abc","title":"Merhaba",
                          "subreddit_name_prefixed":"r/istanbul","permalink":"/r/istanbul/comments/abc/",
                          "created_utc":1762400000}}]}}""", MediaType.APPLICATION_JSON));

        RedditClient client = new RedditClient(builder, PROPS);
        assertThat(client.fetch("spez").iconUrl()).isEqualTo("https://i/x.png");
        assertThat(client.recentActivity("spez")).extracting(ActivityItem::title).containsExactly("r/istanbul: Merhaba");
        server.verify();
    }

    private static String contributionsHtml(LocalDate lastDay, int days) {
        StringBuilder html = new StringBuilder("<table>");
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = lastDay.minusDays(i);
            html.append("<td tabindex=\"0\" data-date=\"").append(day).append("\" id=\"d").append(i)
                    .append("\" data-level=\"").append(i % 5).append("\" class=\"ContributionCalendar-day\"></td>");
        }
        return html.append("</table>").toString();
    }
}
