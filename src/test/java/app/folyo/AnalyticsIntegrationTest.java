package app.folyo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.folyo.analytics.AnalyticsService;
import app.folyo.auth.FolyoPrincipal;
import app.folyo.auth.User;
import app.folyo.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsIntegrationTest {

    private static final String BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0) AppleWebKit/537.36 Chrome/140 Safari/537.36";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    AnalyticsService analytics;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void viewsCountEveryLoadButVisitorsOncePerDay() throws Exception {
        Owner owner = newOwner();
        String url = "/api/public/profiles/" + owner.username + "/view";

        // CSRF token'i olmadan (sendBeacon) kabul edilir.
        mvc.perform(visit(post(url), "10.0.0.1", BROWSER)).andExpect(status().isNoContent());
        mvc.perform(visit(post(url), "10.0.0.1", BROWSER)).andExpect(status().isNoContent());
        mvc.perform(visit(post(url), "10.0.0.2", BROWSER)).andExpect(status().isNoContent());
        // Sayilmayanlar: link onizleyicisi, user-agent'siz istemci, sayfanin sahibi.
        mvc.perform(visit(post(url), "10.0.0.3", "WhatsApp/2.23.20.0")).andExpect(status().isNoContent());
        mvc.perform(visit(post(url), "10.0.0.4", null)).andExpect(status().isNoContent());
        mvc.perform(visit(post(url), "10.0.0.5", BROWSER).with(owner.auth)).andExpect(status().isNoContent());

        mvc.perform(get("/api/me/stats").with(owner.auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series", hasSize(7)))
                .andExpect(jsonPath("$.series[6].day").value(LocalDate.now(ZoneId.of("Europe/Istanbul")).toString()))
                .andExpect(jsonPath("$.series[6].views").value(3))
                .andExpect(jsonPath("$.series[6].visitors").value(2))
                .andExpect(jsonPath("$.totals.views").value(3))
                .andExpect(jsonPath("$.previous.views").value(0));

        mvc.perform(get("/api/me/stats?days=30").with(owner.auth)).andExpect(jsonPath("$.series", hasSize(30)));
        mvc.perform(get("/api/me/stats?days=5").with(owner.auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/stats")).andExpect(status().isUnauthorized());
        mvc.perform(visit(post("/api/public/profiles/yok-boyle/view"), "10.0.0.1", BROWSER)).andExpect(status().isNotFound());

        // Baska bir sahibin istatistigi bu sayfanin ziyaretlerini gormez.
        Owner other = newOwner();
        mvc.perform(get("/api/me/stats").with(other.auth)).andExpect(jsonPath("$.totals.views").value(0));
    }

    @Test
    void clicksAreUniquePerVisitorAndIgnoreHiddenCardsAndOwner() throws Exception {
        Owner owner = newOwner();
        String github = createCard(owner, "https://github.com/a");
        String insta = createCard(owner, "https://instagram.com/a");

        for (int i = 0; i < 3; i++) {
            mvc.perform(visit(post("/api/public/cards/" + github + "/click"), "10.1.0.1", BROWSER))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(visit(post("/api/public/cards/" + github + "/click"), "10.1.0.2", BROWSER));
        mvc.perform(visit(post("/api/public/cards/" + insta + "/click"), "10.1.0.1", BROWSER));
        mvc.perform(visit(post("/api/public/cards/" + insta + "/click"), "10.1.0.9", BROWSER).with(owner.auth));
        mvc.perform(visit(post("/api/public/cards/" + UUID.randomUUID() + "/click"), "10.1.0.1", BROWSER))
                .andExpect(status().isNoContent());

        mvc.perform(json(patch("/api/me/cards/" + insta), "{\"visible\":false}").with(owner.auth).with(csrf()));
        mvc.perform(visit(post("/api/public/cards/" + insta + "/click"), "10.1.0.3", BROWSER));

        mvc.perform(get("/api/me/stats").with(owner.auth))
                .andExpect(jsonPath("$.totals.clicks").value(3))
                .andExpect(jsonPath("$.topCards", hasSize(2)))
                .andExpect(jsonPath("$.topCards[0].cardId").value(github))
                .andExpect(jsonPath("$.topCards[0].clicks").value(2))
                .andExpect(jsonPath("$.topCards[0].config.url").value("https://github.com/a"))
                .andExpect(jsonPath("$.topCards[1].clicks").value(1));
    }

    @Test
    void concurrentViewsAreNotLost() throws Exception {
        Owner owner = newOwner();
        String url = "/api/public/profiles/" + owner.username + "/view";
        int visitors = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(visitors)) {
            for (int i = 0; i < visitors; i++) {
                String ip = "10.2.0." + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(visit(post(url), ip, BROWSER)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(204);
            }
        }
        mvc.perform(get("/api/me/stats").with(owner.auth))
                .andExpect(jsonPath("$.totals.views").value(visitors))
                .andExpect(jsonPath("$.totals.visitors").value(visitors));
    }

    @Test
    void dailyCleanupForgetsVisitorHashesButKeepsTotals() throws Exception {
        Owner owner = newOwner();
        mvc.perform(visit(post("/api/public/profiles/" + owner.username + "/view"), "10.3.0.1", BROWSER));
        LocalDate old = LocalDate.now(ZoneId.of("Europe/Istanbul")).minusDays(3);
        jdbc.update("insert into daily_salts (day, salt) values (?, ?) on conflict do nothing", old, new byte[32]);
        jdbc.update("insert into visit_marks (scope_id, day, visitor_hash) values (?, ?, ?)", UUID.randomUUID(), old,
                new byte[32]);

        analytics.dailyCleanup();

        assertThat(jdbc.queryForObject("select count(*) from visit_marks where day < current_date - 1", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from daily_salts where day < current_date - 1", Integer.class))
                .isZero();
        mvc.perform(get("/api/me/stats").with(owner.auth)).andExpect(jsonPath("$.totals.views").value(1));
    }

    // --- yardimcilar ---

    private record Owner(RequestPostProcessor auth, String username) {
    }

    private Owner newOwner() throws Exception {
        String subject = UUID.randomUUID().toString();
        User user = userService.signIn("test", subject, null, "Test", null);
        RequestPostProcessor auth = authentication(UsernamePasswordAuthenticationToken.authenticated(
                new FolyoPrincipal(user.getId(), user.getName()), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        String username = "a" + subject.replace("-", "").substring(0, 12);
        mvc.perform(json(post("/api/me/profile"), "{\"username\":\"" + username + "\",\"displayName\":\"Sahip\"}")
                .with(auth).with(csrf())).andExpect(status().isCreated());
        return new Owner(auth, username);
    }

    private String createCard(Owner owner, String url) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/me/cards"), "{\"type\":\"LINK\",\"config\":{\"url\":\"" + url + "\"}}")
                .with(owner.auth).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id");
    }

    private static MockHttpServletRequestBuilder visit(MockHttpServletRequestBuilder builder, String ip, String userAgent) {
        builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
        return userAgent == null ? builder : builder.header("User-Agent", userAgent);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
