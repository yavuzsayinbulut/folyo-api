package app.folyo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.folyo.auth.FolyoPrincipal;
import app.folyo.auth.User;
import app.folyo.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "folyo.mobile.redirect-prefixes=folyo://")
@AutoConfigureMockMvc
class MobileAuthIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    JdbcTemplate jdbc;

    private final SecureRandom random = new SecureRandom();

    @Test
    void pkceCodeBecomesBearerTokenOnlyWithMatchingVerifierAndOnlyOnce() throws Exception {
        RequestPostProcessor webSession = webUser();
        String verifier = verifier();

        String code = requestCode(webSession, challenge(verifier));
        mvc.perform(exchange(code, verifier())).andExpect(status().isBadRequest());
        // Yanlis verifier denemesi kodu tuketti; dogru verifier ile de artik kullanilamaz.
        mvc.perform(exchange(code, verifier)).andExpect(status().isBadRequest());

        code = requestCode(webSession, challenge(verifier));
        String token = JsonPath.read(mvc.perform(exchange(code, verifier))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(exchange(code, verifier)).andExpect(status().isBadRequest());

        // Token cerezsiz calisir, CSRF istemez ve oturum acmaz.
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Mobil"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        mvc.perform(json(post("/api/me/profile"), "{\"username\":\"m" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"displayName\":\"Mobil\"}").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/me/cards"), "{\"type\":\"TEXT\",\"config\":{\"text\":\"telefondan\"}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        // Cikis yalniz bu token'i iptal eder.
        mvc.perform(delete("/api/me/mobile/token").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsForeignRedirectsBadChallengesExpiredCodesAndUnknownTokens() throws Exception {
        RequestPostProcessor webSession = webUser();
        String challenge = challenge(verifier());
        mvc.perform(json(post("/api/me/mobile/codes"),
                        "{\"codeChallenge\":\"" + challenge + "\",\"redirectUri\":\"https://evil.example/cb\"}")
                        .with(webSession).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/me/mobile/codes"), "{\"codeChallenge\":\"short\",\"redirectUri\":\"folyo://auth\"}")
                        .with(webSession).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/me/mobile/codes"),
                        "{\"codeChallenge\":\"" + challenge + "\",\"redirectUri\":\"folyo://auth\"}").with(csrf()))
                .andExpect(status().isUnauthorized());

        String verifier = verifier();
        String code = requestCode(webSession, challenge(verifier));
        jdbc.update("update mobile_auth_codes set expires_at = now() - interval '1 second'");
        mvc.perform(exchange(code, verifier)).andExpect(status().isBadRequest());

        mvc.perform(get("/api/me").header("Authorization", "Bearer uydurma-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentExchangesOfOneCodeIssueExactlyOneToken() throws Exception {
        String verifier = verifier();
        String code = requestCode(webUser(), challenge(verifier));
        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(exchange(code, verifier)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 400).hasSize(attempts - 1);
        }
    }

    // --- yardimcilar ---

    private RequestPostProcessor webUser() {
        User user = userService.signIn("test", UUID.randomUUID().toString(), null, "Mobil", null);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new FolyoPrincipal(user.getId(), user.getName()), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private String requestCode(RequestPostProcessor webSession, String challenge) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/me/mobile/codes"),
                        "{\"codeChallenge\":\"" + challenge + "\",\"redirectUri\":\"folyo://auth\"}")
                        .with(webSession).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");
    }

    private MockHttpServletRequestBuilder exchange(String code, String verifier) {
        return json(post("/api/mobile/token"),
                "{\"code\":\"" + code + "\",\"codeVerifier\":\"" + verifier + "\",\"deviceName\":\"iPhone\"}");
    }

    private String verifier() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
