package app.folyo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.folyo.activity.ActivitySync;
import app.folyo.auth.FolyoPrincipal;
import app.folyo.auth.User;
import app.folyo.auth.UserService;
import app.folyo.config.FolyoProperties;
import app.folyo.connection.ConnectionMissingException;
import app.folyo.connection.ConnectionProvider;
import app.folyo.connection.ConnectionService;
import app.folyo.connection.TokenGrant;
import app.folyo.media.PhotoFixtures;
import app.folyo.profile.ProfileRepository;
import app.folyo.provider.AccountNotFoundException;
import app.folyo.provider.ActivityItem;
import app.folyo.provider.AuthorizationRevokedException;
import app.folyo.provider.GitHubClient;
import app.folyo.provider.LastFmClient;
import app.folyo.provider.LiveData;
import app.folyo.provider.RedditClient;
import app.folyo.provider.SpotifyClient;
import app.folyo.provider.StravaClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LiveFeaturesIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    ProfileRepository profiles;
    @Autowired
    ConnectionService connectionService;
    @Autowired
    ActivitySync activitySync;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FolyoProperties properties;

    @MockitoBean
    GitHubClient github;
    @MockitoBean
    SpotifyClient spotify;
    @MockitoBean
    StravaClient strava;
    @MockitoBean
    RedditClient reddit;
    @MockitoBean
    LastFmClient lastfm;

    // --- canli kartlar ---

    @Test
    void liveGitHubCardIsCachedAndHiddenCardsAreNotServed() throws Exception {
        String gh = "octo-" + UUID.randomUUID().toString().substring(0, 8);
        given(github.fetch(gh)).willReturn(gitHubData(gh));
        RequestPostProcessor owner = userWithProfile();

        mvc.perform(json(post("/api/me/cards"), "{\"type\":\"GITHUB\",\"config\":{\"username\":\"bad name!\"}}")
                        .with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        String card = createCard(owner, "{\"type\":\"GITHUB\",\"config\":{\"username\":\"https://github.com/" + gh + "/\"}}");
        mvc.perform(get("/api/me/cards").with(owner))
                .andExpect(jsonPath("$[0].config.username").value(gh))
                .andExpect(jsonPath("$[0].size").value("WIDE"));

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/public/cards/" + card + "/live"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OK"))
                    .andExpect(jsonPath("$.data.followers").value(42));
        }
        verify(github, times(1)).fetch(gh);

        mvc.perform(json(patch("/api/me/cards/" + card), "{\"visible\":false}").with(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/cards/" + card + "/live")).andExpect(status().isNotFound());

        String link = createCard(owner, "{\"type\":\"LINK\",\"config\":{\"url\":\"https://x.com/a\"}}");
        mvc.perform(get("/api/public/cards/" + link + "/live")).andExpect(status().isNotFound());
    }

    @Test
    void liveCardReportsMissingAccountAndUnconfiguredProvider() throws Exception {
        RequestPostProcessor owner = userWithProfile();
        given(github.fetch("ghost-user")).willThrow(new AccountNotFoundException("yok"));
        given(lastfm.nowPlaying("someone")).willThrow(new app.folyo.provider.ProviderNotConfiguredException("Last.fm"));

        String gh = createCard(owner, "{\"type\":\"GITHUB\",\"config\":{\"username\":\"ghost-user\"}}");
        String fm = createCard(owner, "{\"type\":\"LASTFM\",\"config\":{\"username\":\"someone\"}}");

        mvc.perform(get("/api/public/cards/" + gh + "/live")).andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mvc.perform(get("/api/public/cards/" + fm + "/live")).andExpect(jsonPath("$.status").value("NOT_CONFIGURED"));
    }

    // --- hesap baglama ---

    @Test
    void spotifyCardRequiresConnection() throws Exception {
        RequestPostProcessor owner = userWithProfile();
        mvc.perform(json(post("/api/me/cards"), "{\"type\":\"SPOTIFY\"}").with(owner).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Önce Spotify hesabını bağla"));
    }

    @Test
    void oauthStateIsSingleUseAndBoundToUser() throws Exception {
        stubSpotifyAuthorize();
        given(spotify.exchangeCode("good-code", properties.webUrl() + "/api/me/connections/spotify/callback"))
                .willReturn(new TokenGrant("AT-plain", "RT-plain", Instant.now().plusSeconds(3600), "s", "sp-1", "Yavuz"));
        RequestPostProcessor owner = userWithProfile();
        RequestPostProcessor stranger = userWithProfile();
        MockHttpSession session = new MockHttpSession();

        String state = startConnect(owner, session);
        mvc.perform(callback(stranger, session, "good-code", state)).andExpect(status().isBadRequest());
        // Ilk (basarisiz) deneme state'i tuketti; dogru kullanici bile ayni state'i kullanamaz.
        mvc.perform(callback(owner, session, "good-code", state)).andExpect(status().isBadRequest());

        state = startConnect(owner, session);
        mvc.perform(callback(owner, session, "good-code", "wrong-" + state)).andExpect(status().isBadRequest());

        state = startConnect(owner, session);
        mvc.perform(callback(owner, session, "good-code", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", properties.webUrl() + "/edit?connected=spotify"));

        mvc.perform(get("/api/me/connections").with(owner))
                .andExpect(jsonPath("$[?(@.provider=='spotify')].connected").value(true))
                .andExpect(jsonPath("$[?(@.provider=='spotify')].externalUsername").value("Yavuz"));
        mvc.perform(get("/api/me/connections").with(stranger))
                .andExpect(jsonPath("$[?(@.provider=='spotify')].connected").value(false));

        UUID profileId = profileIdOf(owner);
        byte[] stored = jdbc.queryForObject("select access_token_enc from connections where profile_id = ?",
                byte[].class, profileId);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("AT-plain");

        // Baglanti varken Spotify karti eklenebilir.
        createCard(owner, "{\"type\":\"SPOTIFY\"}");
    }

    @Test
    void concurrentRequestsRefreshAnExpiredTokenOnlyOnce() throws Exception {
        RequestPostProcessor owner = userWithProfile();
        UUID profileId = connectSpotify(owner, Instant.now().minusSeconds(30));
        given(spotify.refresh("RT-plain")).willAnswer(invocation -> {
            Thread.sleep(300);
            return new TokenGrant("AT-new", null, Instant.now().plusSeconds(3600), null, null, null);
        });

        int callers = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return connectionService.accessToken(profileId, ConnectionProvider.SPOTIFY);
                }));
            }
            start.countDown();
            Set<String> tokens = ConcurrentHashMap.newKeySet();
            for (Future<String> result : results) {
                tokens.add(result.get());
            }
            assertThat(tokens).containsExactly("AT-new");
        }
        verify(spotify, times(1)).refresh("RT-plain");
        // Yenilemede yeni refresh token gelmedi; eskisi korunur.
        byte[] refresh = jdbc.queryForObject("select refresh_token_enc from connections where profile_id = ?",
                byte[].class, profileId);
        assertThat(refresh).isNotNull();
    }

    @Test
    void revokedAuthorizationMarksConnectionAndCardShowsNotConnected() throws Exception {
        RequestPostProcessor owner = userWithProfile();
        UUID profileId = connectSpotify(owner, Instant.now().minusSeconds(30));
        String card = createCard(owner, "{\"type\":\"SPOTIFY\"}");
        given(spotify.refresh("RT-plain")).willThrow(new AuthorizationRevokedException("geri alindi"));

        assertThatThrownBy(() -> connectionService.accessToken(profileId, ConnectionProvider.SPOTIFY))
                .isInstanceOf(ConnectionMissingException.class);
        assertThat(jdbc.queryForObject("select status from connections where profile_id = ?", String.class, profileId))
                .isEqualTo("REVOKED");
        mvc.perform(get("/api/public/cards/" + card + "/live")).andExpect(jsonPath("$.status").value("NOT_CONNECTED"));
        mvc.perform(get("/api/me/connections").with(owner))
                .andExpect(jsonPath("$[?(@.provider=='spotify')].connected").value(false));
    }

    // --- aktivite akisi ---

    @Test
    void activitySyncIsIdempotentAndFollowsCardVisibility() throws Exception {
        String gh = "act-" + UUID.randomUUID().toString().substring(0, 8);
        given(github.recentActivity(gh)).willReturn(List.of(
                new ActivityItem("e-1", "push", gh + "/app reposuna push (main)", "https://github.com/" + gh + "/app",
                        Instant.parse("2026-11-05T10:00:00Z")),
                new ActivityItem("e-2", "star", "spring/boot reposuna yıldız verdi", "https://github.com/spring/boot",
                        Instant.parse("2026-11-04T10:00:00Z"))));
        RequestPostProcessor owner = userWithProfile();
        String username = usernameOf(owner);
        String card = createCard(owner, "{\"type\":\"GITHUB\",\"config\":{\"username\":\"" + gh + "\"}}");

        // Kart eklenince senkron arka planda calisir.
        String activityUrl = "/api/public/profiles/" + username + "/activity";
        for (int i = 0; i < 50 && activityCount(activityUrl) < 2; i++) {
            Thread.sleep(100);
        }
        mvc.perform(get(activityUrl)).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value(gh + "/app reposuna push (main)"));

        UUID profileId = profileIdOf(owner);
        activitySync.syncProfile(profileId);
        activitySync.syncProfile(profileId);
        assertThat(jdbc.queryForObject("select count(*) from activity_events where profile_id = ?", Integer.class,
                profileId)).isEqualTo(2);

        mvc.perform(json(patch("/api/me/cards/" + card), "{\"visible\":false}").with(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(activityUrl)).andExpect(jsonPath("$", hasSize(0)));
    }

    // --- galeri ---

    @Test
    void galleryUploadStripsMetadataAndEnforcesOwnership() throws Exception {
        RequestPostProcessor owner = userWithProfile();
        String gallery = createCard(owner, "{\"type\":\"GALLERY\",\"config\":{\"title\":\"Karaköy\"}}");
        String link = createCard(owner, "{\"type\":\"LINK\",\"config\":{\"url\":\"https://vsco.co/a\"}}");
        var photo = new MockMultipartFile("file", "IMG_0001.jpg", "image/jpeg",
                PhotoFixtures.jpegWithExifOrientation(1200, 800, 6));

        String media = JsonPath.read(mvc.perform(multipart("/api/me/cards/" + gallery + "/media").file(photo)
                        .with(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.width").value(800))
                .andReturn().getResponse().getContentAsString(), "$.id");

        byte[] served = mvc.perform(get("/api/public/media/" + media))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("GPS");

        mvc.perform(get("/api/public/profiles/" + usernameOf(owner)))
                .andExpect(jsonPath("$.cards[0].media", hasSize(1)))
                .andExpect(jsonPath("$.cards[0].media[0].url").value("/api/public/media/" + media))
                .andExpect(jsonPath("$.cards[1].media").doesNotExist());

        var text = new MockMultipartFile("file", "not-a-photo.jpg", "image/jpeg", "merhaba".getBytes());
        mvc.perform(multipart("/api/me/cards/" + gallery + "/media").file(text).with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/me/cards/" + link + "/media").file(photo).with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        RequestPostProcessor stranger = userWithProfile();
        mvc.perform(multipart("/api/me/cards/" + gallery + "/media").file(photo).with(stranger).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/media/" + media).with(stranger).with(csrf())).andExpect(status().isNotFound());

        // Gizli galerinin fotografi ziyaretciye 404, sahibine acik.
        mvc.perform(json(patch("/api/me/cards/" + gallery), "{\"visible\":false}").with(owner).with(csrf()));
        mvc.perform(get("/api/public/media/" + media)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/media/" + media).with(owner)).andExpect(status().isOk());

        // Kart silinince dosya da diskten silinir.
        String key = jdbc.queryForObject("select storage_key from media where id = ?", String.class, UUID.fromString(media));
        Path file = Path.of(properties.mediaDir()).resolve(key);
        assertThat(Files.exists(file)).isTrue();
        mvc.perform(delete("/api/me/cards/" + gallery).with(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(Files.exists(file)).isFalse();
    }

    // --- yardimcilar ---

    private UUID connectSpotify(RequestPostProcessor owner, Instant expiresAt) throws Exception {
        stubSpotifyAuthorize();
        given(spotify.exchangeCode(anyString(), anyString()))
                .willReturn(new TokenGrant("AT-plain", "RT-plain", expiresAt, "s", "sp-1", "Yavuz"));
        MockHttpSession session = new MockHttpSession();
        String state = startConnect(owner, session);
        mvc.perform(callback(owner, session, "code", state)).andExpect(status().isFound());
        return profileIdOf(owner);
    }

    private void stubSpotifyAuthorize() {
        given(spotify.configured()).willReturn(true);
        given(spotify.authorizeUrl(anyString(), anyString()))
                .willAnswer(invocation -> "https://accounts.spotify.com/authorize?state=" + invocation.getArgument(0));
    }

    private String startConnect(RequestPostProcessor user, MockHttpSession session) throws Exception {
        String location = mvc.perform(get("/api/me/connections/spotify/start").with(user).session(session))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        return location.substring(location.indexOf("state=") + "state=".length());
    }

    private MockHttpServletRequestBuilder callback(RequestPostProcessor user, MockHttpSession session, String code,
            String state) {
        return get("/api/me/connections/spotify/callback").param("code", code).param("state", state).with(user)
                .session(session);
    }

    private int activityCount(String url) throws Exception {
        List<?> list = JsonPath.read(mvc.perform(get(url)).andReturn().getResponse().getContentAsString(), "$");
        return list.size();
    }

    private final java.util.Map<RequestPostProcessor, String> usernames = new java.util.HashMap<>();

    private RequestPostProcessor userWithProfile() throws Exception {
        String subject = UUID.randomUUID().toString();
        User user = userService.signIn("test", subject, null, "Test", null);
        RequestPostProcessor auth = authentication(UsernamePasswordAuthenticationToken.authenticated(
                new FolyoPrincipal(user.getId(), user.getName()), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        String username = "l" + subject.replace("-", "").substring(0, 12);
        mvc.perform(json(post("/api/me/profile"), "{\"username\":\"" + username + "\",\"displayName\":\"Sahip\"}")
                .with(auth).with(csrf())).andExpect(status().isCreated());
        usernames.put(auth, username);
        return auth;
    }

    private String usernameOf(RequestPostProcessor user) {
        return usernames.get(user);
    }

    private UUID profileIdOf(RequestPostProcessor user) {
        return profiles.findByUsername(usernameOf(user)).orElseThrow().getId();
    }

    private String createCard(RequestPostProcessor owner, String body) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/me/cards"), body).with(owner).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static LiveData.GitHub gitHubData(String login) {
        return new LiveData.GitHub(login, "Octo", null, "https://github.com/" + login, 42, 8, 120,
                List.of(0, 1, 2, 3, 4), List.of(new LiveData.Repo("big", "https://github.com/" + login + "/big", 120, "Java")));
    }
}
