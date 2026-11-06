package app.folyo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.folyo.auth.FolyoPrincipal;
import app.folyo.auth.User;
import app.folyo.auth.UserService;
import app.folyo.notification.ExpoPushClient;
import app.folyo.notification.NotificationService;
import app.folyo.notification.PushDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    PushDispatcher dispatcher;
    @Autowired
    NotificationService notifications;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ExpoPushClient expo;

    @Test
    void guestbookMessageIsQueuedInSameTransactionAndRespectsSettings() throws Exception {
        Person owner = personWithPage();
        Person visitor = person();
        String url = "/api/profiles/" + owner.username + "/guestbook";

        mvc.perform(json(post(url), "{\"body\":\"Karaköy kareleri harika\"}").with(visitor.auth).with(csrf()))
                .andExpect(status().isCreated());
        assertThat(outbox(owner)).hasSize(1);
        assertThat(jdbc.queryForObject("select body from push_outbox where user_id = ?", String.class, owner.userId))
                .isEqualTo("Test: Karaköy kareleri harika");

        // Sahibin kendi defterine yazdigi mesaj bildirim uretmez.
        mvc.perform(json(post(url), "{\"body\":\"Kendime not\"}").with(owner.auth).with(csrf()));
        // Ayar kapaliysa kuyruga girmez.
        mvc.perform(json(put("/api/me/notification-settings"), "{\"guestbook\":false,\"weeklySummary\":true}")
                .with(owner.auth).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/me/notification-settings").with(owner.auth)).andExpect(jsonPath("$.guestbook").value(false));
        mvc.perform(json(post(url), "{\"body\":\"İkinci mesaj\"}").with(visitor.auth).with(csrf()));
        assertThat(outbox(owner)).hasSize(1);

        // Gecersiz mesaj (cok kisa) kaydedilmez; bildirim de olusmaz.
        mvc.perform(json(put("/api/me/notification-settings"), "{\"guestbook\":true,\"weeklySummary\":true}")
                .with(owner.auth).with(csrf()));
        mvc.perform(json(post(url), "{\"body\":\"ab\"}").with(visitor.auth).with(csrf())).andExpect(status().isBadRequest());
        assertThat(outbox(owner)).hasSize(1);
    }

    @Test
    void dispatcherSendsToEveryDeviceRecordsTicketsAndDropsDeadDevices() throws Exception {
        Person owner = personWithPage();
        registerDevice(owner, "ExponentPushToken[iphone-" + owner.userId + "]");
        registerDevice(owner, "ExponentPushToken[old-" + owner.userId + "]");
        mvc.perform(json(post("/api/me/devices"), "{\"expoToken\":\"bozuk\",\"platform\":\"ios\"}").with(owner.auth).with(csrf()))
                .andExpect(status().isBadRequest());
        enqueueGuestbook(owner);

        List<ExpoPushClient.Message> sent = new CopyOnWriteArrayList<>();
        given(expo.send(anyList())).willAnswer(invocation -> {
            List<ExpoPushClient.Message> messages = invocation.getArgument(0);
            List<ExpoPushClient.Ticket> tickets = new ArrayList<>();
            for (ExpoPushClient.Message message : messages) {
                sent.add(message);
                tickets.add(message.to().contains("old-")
                        ? new ExpoPushClient.Ticket(null, "DeviceNotRegistered")
                        : new ExpoPushClient.Ticket("ticket-" + UUID.randomUUID(), null));
            }
            return tickets;
        });

        dispatcher.dispatchDue();

        assertThat(sent).filteredOn(m -> m.to().contains(owner.userId.toString())).hasSize(2)
                .allSatisfy(m -> assertThat(m.data()).containsEntry("path", "/"));
        assertThat(jdbc.queryForObject("select sent_at is not null from push_outbox where user_id = ?", Boolean.class,
                owner.userId)).isTrue();
        assertThat(devices(owner)).containsExactly("ExponentPushToken[iphone-" + owner.userId + "]");
        assertThat(jdbc.queryForObject("""
                select count(*) from push_receipts r join device_push_tokens d on d.id = r.device_token_id
                where d.user_id = ?""", Integer.class, owner.userId)).isEqualTo(1);

        // Expo sonradan makbuzla "cihaz kayitli degil" derse o cihaz da silinir.
        jdbc.update("update push_receipts set created_at = now() - interval '1 hour'");
        given(expo.receipts(anyList())).willAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            Map<String, String> result = new java.util.HashMap<>();
            ids.forEach(id -> result.put(id, "DeviceNotRegistered"));
            return result;
        });
        dispatcher.checkReceipts();
        assertThat(devices(owner)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from push_receipts", Integer.class)).isZero();
    }

    @Test
    void temporaryFailuresBackOffAndGiveUpAfterMaxAttempts() throws Exception {
        Person owner = personWithPage();
        registerDevice(owner, "ExponentPushToken[retry-" + owner.userId + "]");
        enqueueGuestbook(owner);
        given(expo.send(anyList())).willThrow(new ExpoPushClient.PushUnavailableException("kapalı", null));

        dispatcher.dispatchDue();
        assertThat(jdbc.queryForMap("""
                select attempts, sent_at is null as pending, next_attempt_at > now() as later
                from push_outbox where user_id = ?""", owner.userId))
                .containsEntry("attempts", 1).containsEntry("pending", true).containsEntry("later", true);

        // Bekleme suresi dolmadan tekrar denenmez.
        dispatcher.dispatchDue();
        assertThat(jdbc.queryForObject("select attempts from push_outbox where user_id = ?", Integer.class, owner.userId))
                .isEqualTo(1);

        for (int i = 0; i < 4; i++) {
            jdbc.update("update push_outbox set next_attempt_at = now() where user_id = ?", owner.userId);
            dispatcher.dispatchDue();
        }
        assertThat(jdbc.queryForMap("select attempts, failed_at is not null as failed from push_outbox where user_id = ?",
                owner.userId)).containsEntry("attempts", 5).containsEntry("failed", true);
    }

    @Test
    void concurrentDispatchersNeverSendTheSameNotificationTwice() throws Exception {
        List<Person> owners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Person owner = personWithPage();
            registerDevice(owner, "ExponentPushToken[par-" + owner.userId + "]");
            enqueueGuestbook(owner);
            owners.add(owner);
        }
        List<String> sentTo = new CopyOnWriteArrayList<>();
        given(expo.send(anyList())).willAnswer(invocation -> {
            Thread.sleep(50);
            List<ExpoPushClient.Message> messages = invocation.getArgument(0);
            messages.forEach(m -> sentTo.add(m.to()));
            return messages.stream().map(m -> new ExpoPushClient.Ticket("t-" + UUID.randomUUID(), null)).toList();
        });

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            for (int i = 0; i < 3; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return dispatcher.dispatchDue();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get();
            }
        }
        for (Person owner : owners) {
            assertThat(sentTo).filteredOn(to -> to.contains(owner.userId.toString())).hasSize(1);
        }
    }

    @Test
    void weeklySummaryIsQueuedOncePerWeekAndWidgetSummarizesThePage() throws Exception {
        Person owner = personWithPage();
        registerDevice(owner, "ExponentPushToken[weekly-" + owner.userId + "]");

        notifications.enqueueWeeklySummaries();
        notifications.enqueueWeeklySummaries();
        assertThat(jdbc.queryForObject("select count(*) from push_outbox where user_id = ? and kind = 'weekly_summary'",
                Integer.class, owner.userId)).isEqualTo(1);

        Person visitor = person();
        mvc.perform(json(post("/api/profiles/" + owner.username + "/guestbook"), "{\"body\":\"Selam, güzel sayfa\"}")
                .with(visitor.auth).with(csrf()));
        mvc.perform(get("/api/me/widget").with(owner.auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(owner.username))
                .andExpect(jsonPath("$.last7Days.length()").value(7))
                .andExpect(jsonPath("$.latestMessage.body").value("Selam, güzel sayfa"));
    }

    // --- yardimcilar ---

    private record Person(RequestPostProcessor auth, UUID userId, String username) {
    }

    private Person person() {
        User user = userService.signIn("test", UUID.randomUUID().toString(), null, "Test", null);
        return new Person(authentication(UsernamePasswordAuthenticationToken.authenticated(
                new FolyoPrincipal(user.getId(), user.getName()), null, List.of(new SimpleGrantedAuthority("ROLE_USER")))),
                user.getId(), null);
    }

    private Person personWithPage() throws Exception {
        Person person = person();
        String username = "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mvc.perform(json(post("/api/me/profile"), "{\"username\":\"" + username + "\",\"displayName\":\"Sahip\"}")
                .with(person.auth).with(csrf())).andExpect(status().isCreated());
        return new Person(person.auth, person.userId, username);
    }

    private void registerDevice(Person person, String token) throws Exception {
        mvc.perform(json(post("/api/me/devices"), "{\"expoToken\":\"" + token + "\",\"platform\":\"ios\"}")
                .with(person.auth).with(csrf())).andExpect(status().isNoContent());
    }

    private void enqueueGuestbook(Person owner) throws Exception {
        mvc.perform(json(post("/api/profiles/" + owner.username + "/guestbook"), "{\"body\":\"Merhaba dünya\"}")
                .with(person().auth).with(csrf())).andExpect(status().isCreated());
    }

    private List<String> outbox(Person person) {
        return jdbc.queryForList("select id::text from push_outbox where user_id = ?", String.class, person.userId);
    }

    private List<String> devices(Person person) {
        return jdbc.queryForList("select expo_token from device_push_tokens where user_id = ?", String.class, person.userId);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
