package app.folyo.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kuyruktaki bildirimleri Expo Push'a iletir.
 * - Teslim "en az bir kez"dir: Expo kabul edip commit basarisiz olursa bildirim tekrar gidebilir; kaybolmaz.
 * - Gecici hatada ustel bekleme ile 5 kez denenir, sonra "basarisiz" isaretlenir.
 * - Gecersizlesmis cihazlar (DeviceNotRegistered) hem bilette hem sonradan gelen makbuzda silinir.
 */
@Service
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);
    static final int BATCH = 50;
    static final int MAX_ATTEMPTS = 5;
    private static final Duration RECEIPT_DELAY = Duration.ofMinutes(15);

    private final NotificationRepository repository;
    private final ExpoPushClient expo;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public PushDispatcher(NotificationRepository repository, ExpoPushClient expo, TransactionTemplate transaction) {
        this.repository = repository;
        this.expo = expo;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${folyo.push.dispatch-interval:PT10S}",
            initialDelayString = "${folyo.push.dispatch-initial-delay:PT20S}")
    public void dispatchScheduled() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Bildirim gonderimi basarisiz", e);
        }
    }

    /** @return bu turda islenen kuyruk kaydi sayisi */
    public int dispatchDue() {
        Integer processed = transaction.execute(status -> {
            List<NotificationRepository.OutboxItem> due = repository.claimDue(BATCH);
            for (NotificationRepository.OutboxItem item : due) {
                deliver(item);
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }

    private void deliver(NotificationRepository.OutboxItem item) {
        List<NotificationRepository.Device> devices = repository.devicesOf(item.userId());
        if (devices.isEmpty()) {
            // Kullanici cihazini kaldirmis; gonderilecek yer yok.
            repository.markSent(item.id());
            return;
        }
        Map<String, Object> data = parse(item.data());
        List<ExpoPushClient.Message> messages = new ArrayList<>();
        for (NotificationRepository.Device device : devices) {
            messages.add(new ExpoPushClient.Message(device.expoToken(), item.title(), item.body(), data));
        }
        try {
            List<ExpoPushClient.Ticket> tickets = expo.send(messages);
            for (int i = 0; i < tickets.size(); i++) {
                ExpoPushClient.Ticket ticket = tickets.get(i);
                NotificationRepository.Device device = devices.get(i);
                if (ticket.ticketId() != null) {
                    repository.saveReceipt(ticket.ticketId(), device.id());
                } else if (ticket.deviceNotRegistered()) {
                    repository.deleteDevice(device.id());
                }
            }
            repository.markSent(item.id());
        } catch (ExpoPushClient.PushUnavailableException e) {
            if (item.attempts() + 1 >= MAX_ATTEMPTS) {
                repository.markFailed(item.id(), e.getMessage());
            } else {
                Duration backoff = Duration.ofMinutes(1L << item.attempts());
                repository.markRetry(item.id(), Instant.now().plus(backoff), e.getMessage());
            }
        }
    }

    /** Expo, bildirimin cihaza ulasip ulasmadigini ~15 dk sonra makbuzla bildirir. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "${folyo.push.receipts-initial-delay:PT5M}")
    public void checkReceipts() {
        List<NotificationRepository.PendingReceipt> pending = repository.receiptsOlderThan(Instant.now().minus(RECEIPT_DELAY), 1000);
        if (pending.isEmpty()) {
            return;
        }
        Map<String, String> results;
        try {
            results = expo.receipts(pending.stream().map(NotificationRepository.PendingReceipt::ticketId).toList());
        } catch (ExpoPushClient.PushUnavailableException e) {
            log.info("Bildirim makbuzlari alinamadi, sonra denenecek: {}", e.getMessage());
            return;
        }
        List<String> done = new ArrayList<>();
        for (NotificationRepository.PendingReceipt receipt : pending) {
            String ticketId = receipt.ticketId();
            if (results.containsKey(ticketId)) {
                if ("DeviceNotRegistered".equals(results.get(ticketId))) {
                    repository.deleteDevice(receipt.deviceId());
                }
                done.add(ticketId);
            }
        }
        repository.deleteReceipts(done);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String data) {
        try {
            return json.readValue(data, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
