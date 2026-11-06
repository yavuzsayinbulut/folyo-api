package app.folyo.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    public record Device(UUID id, String expoToken) {
    }

    public record Settings(boolean guestbook, boolean weeklySummary) {
    }

    public record PendingReceipt(String ticketId, UUID deviceId) {
    }

    public record OutboxItem(UUID id, UUID userId, String title, String body, String data, int attempts) {
    }

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- cihazlar ---

    /** Ayni token baska kullaniciya kayitliysa (cihazda hesap degisti) yeni kullaniciya tasinir. */
    public void upsertDevice(UUID userId, UUID apiTokenId, String expoToken, String platform) {
        jdbc.sql("""
                insert into device_push_tokens (id, user_id, api_token_id, expo_token, platform)
                values (:id, :user, :apiToken, :token, :platform)
                on conflict (expo_token) do update set user_id = excluded.user_id,
                    api_token_id = excluded.api_token_id, platform = excluded.platform, updated_at = now()""")
                .param("id", UUID.randomUUID()).param("user", userId).param("apiToken", apiTokenId)
                .param("token", expoToken).param("platform", platform).update();
    }

    public List<Device> devicesOf(UUID userId) {
        return jdbc.sql("select id, expo_token from device_push_tokens where user_id = :user")
                .param("user", userId)
                .query((rs, i) -> new Device(rs.getObject("id", UUID.class), rs.getString("expo_token"))).list();
    }

    public void deleteDevice(UUID deviceId) {
        jdbc.sql("delete from device_push_tokens where id = :id").param("id", deviceId).update();
    }

    public void deleteDeviceOfUser(UUID userId, String expoToken) {
        jdbc.sql("delete from device_push_tokens where user_id = :user and expo_token = :token")
                .param("user", userId).param("token", expoToken).update();
    }

    // --- ayarlar ---

    public Settings settings(UUID userId) {
        return jdbc.sql("select guestbook, weekly_summary from notification_settings where user_id = :user")
                .param("user", userId)
                .query((rs, i) -> new Settings(rs.getBoolean("guestbook"), rs.getBoolean("weekly_summary")))
                .optional().orElse(new Settings(true, true));
    }

    public void saveSettings(UUID userId, Settings settings) {
        jdbc.sql("""
                insert into notification_settings (user_id, guestbook, weekly_summary) values (:user, :g, :w)
                on conflict (user_id) do update set guestbook = excluded.guestbook, weekly_summary = excluded.weekly_summary""")
                .param("user", userId).param("g", settings.guestbook()).param("w", settings.weeklySummary()).update();
    }

    /** Haftalik ozet alacak kullanicilar: en az bir cihazi ve sayfasi olan, ozeti kapatmamis olanlar. */
    public List<UUID> usersForWeeklySummary() {
        return jdbc.sql("""
                select distinct d.user_id from device_push_tokens d
                join profiles p on p.user_id = d.user_id
                left join notification_settings s on s.user_id = d.user_id
                where coalesce(s.weekly_summary, true)""")
                .query((rs, i) -> rs.getObject("user_id", UUID.class)).list();
    }

    // --- outbox ---

    /** @return kuyruga yeni yazildiysa true; ayni dedupe anahtari varsa hicbir sey yapmaz. */
    public boolean enqueue(UUID userId, String kind, String title, String body, String dataJson, String dedupeKey) {
        return jdbc.sql("""
                insert into push_outbox (id, user_id, kind, title, body, data, dedupe_key)
                values (:id, :user, :kind, :title, :body, cast(:data as jsonb), :dedupe)
                on conflict (dedupe_key) do nothing""")
                .param("id", UUID.randomUUID()).param("user", userId).param("kind", kind).param("title", title)
                .param("body", body).param("data", dataJson).param("dedupe", dedupeKey).update() == 1;
    }

    /**
     * Gonderim sirasi gelmis kayitlari kilitleyerek alir. SKIP LOCKED: ayni anda calisan baska bir gonderici
     * bu satirlari atlar; bir bildirim iki is tarafindan birden gonderilmez. Cagiran transaction icinde olmalidir.
     */
    public List<OutboxItem> claimDue(int limit) {
        return jdbc.sql("""
                select id, user_id, title, body, data::text as data, attempts from push_outbox
                where sent_at is null and failed_at is null and next_attempt_at <= now()
                order by next_attempt_at limit :limit
                for update skip locked""")
                .param("limit", limit)
                .query((rs, i) -> new OutboxItem(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("title"), rs.getString("body"), rs.getString("data"), rs.getInt("attempts")))
                .list();
    }

    public void markSent(UUID id) {
        jdbc.sql("update push_outbox set sent_at = now(), attempts = attempts + 1 where id = :id").param("id", id).update();
    }

    public void markRetry(UUID id, Instant nextAttempt, String error) {
        jdbc.sql("""
                update push_outbox set attempts = attempts + 1, next_attempt_at = :next, last_error = :error
                where id = :id""")
                .param("id", id).param("next", java.sql.Timestamp.from(nextAttempt)).param("error", truncate(error))
                .update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("update push_outbox set attempts = attempts + 1, failed_at = now(), last_error = :error where id = :id")
                .param("id", id).param("error", truncate(error)).update();
    }

    // --- makbuzlar ---

    public void saveReceipt(String ticketId, UUID deviceId) {
        jdbc.sql("insert into push_receipts (ticket_id, device_token_id) values (:ticket, :device) on conflict do nothing")
                .param("ticket", ticketId).param("device", deviceId).update();
    }

    public List<PendingReceipt> receiptsOlderThan(Instant before, int limit) {
        return jdbc.sql("""
                select ticket_id, device_token_id from push_receipts where created_at < :before
                order by created_at limit :limit""")
                .param("before", java.sql.Timestamp.from(before)).param("limit", limit)
                .query((rs, i) -> new PendingReceipt(rs.getString("ticket_id"), rs.getObject("device_token_id", UUID.class)))
                .list();
    }

    public void deleteReceipts(List<String> ticketIds) {
        if (!ticketIds.isEmpty()) {
            jdbc.sql("delete from push_receipts where ticket_id in (:ids)").param("ids", ticketIds).update();
        }
    }


    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
