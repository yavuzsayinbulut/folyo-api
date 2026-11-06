package app.folyo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.folyo.auth.FolyoPrincipal;
import app.folyo.auth.User;
import app.folyo.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FolyoApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserService userService;

    // --- giris ve yetki ---

    @Test
    void meRequiresLogin() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void devLoginDoesNotExistOutsideDevProfile() throws Exception {
        mvc.perform(json(post("/api/dev/login"), "{\"name\":\"x\"}").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void mutationsWithoutCsrfAreRejected() throws Exception {
        RequestPostProcessor owner = newUser();
        mvc.perform(json(post("/api/me/profile"), claimBody(uniqueName(), "Ad")).with(owner))
                .andExpect(status().isForbidden());
    }

    // --- kullanici adi ---

    @Test
    void claimsUsernameAndRejectsTakenReservedAndInvalidOnes() throws Exception {
        String username = uniqueName();
        RequestPostProcessor owner = newUser();

        mvc.perform(json(post("/api/me/profile"), claimBody("  " + username.toUpperCase() + " ", "Yavuz S."))
                        .with(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));

        mvc.perform(json(post("/api/me/profile"), claimBody(uniqueName(), "Ikinci")).with(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Zaten bir sayfan var"));

        RequestPostProcessor other = newUser();
        mvc.perform(json(post("/api/me/profile"), claimBody(username, "Baska")).with(other).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Bu kullanıcı adı alınmış"));
        mvc.perform(json(post("/api/me/profile"), claimBody("admin", "Baska")).with(other).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Bu ad sistem için ayrılmış"));
        mvc.perform(json(post("/api/me/profile"), claimBody("a!", "Baska")).with(other).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/me/profile"), claimBody("ab..cd", "Baska")).with(other).with(csrf()))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/usernames/" + username + "/availability"))
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/api/usernames/" + uniqueName() + "/availability"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void concurrentClaimsForSameUsernameYieldExactlyOneWinner() throws Exception {
        String username = uniqueName();
        int contenders = 6;
        List<RequestPostProcessor> users = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            users.add(newUser());
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            for (RequestPostProcessor user : users) {
                Callable<Integer> claim = () -> {
                    start.await();
                    return mvc.perform(json(post("/api/me/profile"), claimBody(username, "Yarisci"))
                            .with(user).with(csrf())).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(claim));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(contenders - 1);
        }
    }

    // --- kartlar ---

    @Test
    void cardLifecycleOrderingAndVisibility() throws Exception {
        String username = uniqueName();
        RequestPostProcessor owner = userWithProfile(username);

        String github = createCard(owner, "{\"type\":\"LINK\",\"config\":{\"url\":\"github.com/yavuz\",\"title\":\"GitHub\",\"extra\":\"x\"}}");
        String text = createCard(owner, "{\"type\":\"TEXT\",\"config\":{\"text\":\"Merhaba\"}}");
        String insta = createCard(owner, "{\"type\":\"LINK\",\"size\":\"WIDE\",\"config\":{\"url\":\"https://instagram.com/yavuz\"}}");

        mvc.perform(get("/api/me/cards").with(owner))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].config.url").value("https://github.com/yavuz"))
                .andExpect(jsonPath("$[0].config.extra").doesNotExist())
                .andExpect(jsonPath("$[0].size").value("SMALL"))
                .andExpect(jsonPath("$[1].size").value("WIDE"));

        mvc.perform(json(put("/api/me/cards/order"), "{\"cardIds\":[\"" + insta + "\",\"" + github + "\",\"" + text + "\"]}")
                        .with(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(insta));

        // Eksik ya da tekrar eden id ile siralama reddedilir ve mevcut sira bozulmaz.
        mvc.perform(json(put("/api/me/cards/order"), "{\"cardIds\":[\"" + insta + "\",\"" + github + "\"]}")
                        .with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/me/cards/order"), "{\"cardIds\":[\"" + insta + "\",\"" + insta + "\",\"" + text + "\"]}")
                        .with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/cards").with(owner))
                .andExpect(jsonPath("$[0].id").value(insta))
                .andExpect(jsonPath("$[1].id").value(github))
                .andExpect(jsonPath("$[2].id").value(text));

        mvc.perform(json(patch("/api/me/cards/" + text), "{\"visible\":false,\"size\":\"LARGE\"}").with(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(false))
                .andExpect(jsonPath("$.size").value("LARGE"));

        mvc.perform(get("/api/public/profiles/" + username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexable").value(true))
                .andExpect(jsonPath("$.cards", hasSize(2)))
                .andExpect(jsonPath("$.cards[0].id").value(insta));

        mvc.perform(delete("/api/me/cards/" + github).with(owner).with(csrf())).andExpect(status().isNoContent());
        // Silme sonrasi eklenen kart sona gider, pozisyon cakismasi olmaz.
        String last = createCard(owner, "{\"type\":\"TEXT\",\"config\":{\"text\":\"Son\"}}");
        mvc.perform(get("/api/me/cards").with(owner)).andExpect(jsonPath("$[2].id").value(last));
    }

    @Test
    void rejectsUnsafeOrEmptyCardConfig() throws Exception {
        RequestPostProcessor owner = userWithProfile(uniqueName());
        mvc.perform(json(post("/api/me/cards"), "{\"type\":\"LINK\",\"config\":{\"url\":\"javascript:alert(1)\"}}").with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/me/cards"), "{\"type\":\"LINK\",\"config\":{\"url\":\"ftp://files.example.com\"}}").with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/me/cards"), "{\"type\":\"TEXT\",\"config\":{\"text\":\"   \"}}").with(owner).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Kart metnini yaz"));
    }

    @Test
    void cannotTouchSomeoneElsesCard() throws Exception {
        RequestPostProcessor owner = userWithProfile(uniqueName());
        String card = createCard(owner, "{\"type\":\"TEXT\",\"config\":{\"text\":\"Benim\"}}");

        RequestPostProcessor intruder = userWithProfile(uniqueName());
        mvc.perform(json(patch("/api/me/cards/" + card), "{\"visible\":false}").with(intruder).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/cards/" + card).with(intruder).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/me/cards").with(owner)).andExpect(jsonPath("$[0].visible").value(true));
    }

    // --- ziyaretci defteri ---

    @Test
    void guestbookWritingModerationAndRateLimit() throws Exception {
        String username = uniqueName();
        RequestPostProcessor owner = userWithProfile(username);
        String url = "/api/profiles/" + username + "/guestbook";

        mvc.perform(json(post(url), "{\"body\":\"Merhaba\"}").with(csrf())).andExpect(status().isUnauthorized());

        RequestPostProcessor visitor = newUser();
        String entry = JsonPath.read(mvc.perform(json(post(url), "{\"body\":\"Karakoy karesi harika\"}").with(visitor).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post(url), "{\"body\":\"  \"}").with(visitor).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Önce bir mesaj yaz"));

        mvc.perform(get("/api/public/profiles/" + username + "/guestbook"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].body").value("Karakoy karesi harika"));

        mvc.perform(json(patch("/api/me/guestbook/" + entry), "{\"status\":\"HIDDEN\"}").with(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/public/profiles/" + username + "/guestbook")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/me/guestbook").with(owner)).andExpect(jsonPath("$", hasSize(1)));

        // Ziyaretci baskasinin defterini yonetemez.
        mvc.perform(delete("/api/me/guestbook/" + entry).with(visitor).with(csrf())).andExpect(status().isNotFound());

        for (int i = 2; i <= 5; i++) {
            mvc.perform(json(post(url), "{\"body\":\"Mesaj " + i + "\"}").with(visitor).with(csrf()))
                    .andExpect(status().isCreated());
        }
        mvc.perform(json(post(url), "{\"body\":\"Mesaj 6\"}").with(visitor).with(csrf()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void unlistedProfileIsReachableButNotIndexable() throws Exception {
        String username = uniqueName();
        RequestPostProcessor owner = userWithProfile(username);
        mvc.perform(json(patch("/api/me/profile"),
                        "{\"displayName\":\"Gizli\",\"timezone\":\"Europe/Istanbul\",\"visibility\":\"UNLISTED\"}")
                        .with(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/profiles/" + username))
                .andExpect(jsonPath("$.indexable").value(false))
                .andExpect(jsonPath("$.timezone").value("Europe/Istanbul"));

        mvc.perform(json(patch("/api/me/profile"), "{\"displayName\":\"Gizli\",\"timezone\":\"Mars/Olympus\"}")
                        .with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/profiles/yok-boyle-biri")).andExpect(status().isNotFound());
    }

    // --- yardimcilar ---

    private RequestPostProcessor newUser() {
        String subject = UUID.randomUUID().toString();
        User user = userService.signIn("test", subject, null, "Test " + subject.substring(0, 6), null);
        var principal = new FolyoPrincipal(user.getId(), user.getName());
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private RequestPostProcessor userWithProfile(String username) throws Exception {
        RequestPostProcessor user = newUser();
        mvc.perform(json(post("/api/me/profile"), claimBody(username, "Sahip")).with(user).with(csrf()))
                .andExpect(status().isCreated());
        return user;
    }

    private String createCard(RequestPostProcessor owner, String body) throws Exception {
        String response = mvc.perform(json(post("/api/me/cards"), body).with(owner).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String claimBody(String username, String displayName) {
        return "{\"username\":\"" + username + "\",\"displayName\":\"" + displayName + "\"}";
    }

    private static String uniqueName() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
