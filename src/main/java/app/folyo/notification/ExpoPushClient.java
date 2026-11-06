package app.folyo.notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Expo Push servisi: APNs (iOS) ve FCM (Android) ayrintilarini Expo ustlenir. Erisim token'i opsiyoneldir
 * (Expo projesinde "push guvenligi" aciksa gerekir).
 */
@Component
public class ExpoPushClient {

    static final String SEND_URL = "https://exp.host/--/api/v2/push/send";
    static final String RECEIPTS_URL = "https://exp.host/--/api/v2/push/getReceipts";
    static final int MAX_MESSAGES = 100;

    public record Message(String to, String title, String body, Map<String, Object> data) {
    }

    /** Gonderilen her mesaj icin ayni sirada bir bilet: ya ticketId ya da hata. */
    public record Ticket(String ticketId, String error) {

        public boolean deviceNotRegistered() {
            return "DeviceNotRegistered".equals(error);
        }
    }

    public static class PushUnavailableException extends RuntimeException {
        public PushUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient http;

    public ExpoPushClient(RestClient.Builder builder, @Value("${folyo.expo.access-token:}") String accessToken) {
        builder.defaultHeader("Accept", "application/json");
        if (!accessToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + accessToken);
        }
        this.http = builder.build();
    }

    public List<Ticket> send(List<Message> messages) {
        List<Ticket> tickets = new ArrayList<>();
        for (int from = 0; from < messages.size(); from += MAX_MESSAGES) {
            List<Message> chunk = messages.subList(from, Math.min(messages.size(), from + MAX_MESSAGES));
            List<Map<String, Object>> payload = new ArrayList<>();
            for (Message m : chunk) {
                payload.add(Map.of("to", m.to(), "title", m.title(), "body", m.body(), "data", m.data(),
                        "sound", "default"));
            }
            JsonNode response;
            try {
                response = http.post().uri(SEND_URL).contentType(MediaType.APPLICATION_JSON).body(payload)
                        .retrieve().body(JsonNode.class);
            } catch (RestClientException e) {
                throw new PushUnavailableException("Expo Push yanıt vermedi", e);
            }
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray() || data.size() != chunk.size()) {
                throw new PushUnavailableException("Expo Push beklenmeyen yanıt döndü", null);
            }
            for (JsonNode ticket : data) {
                if ("ok".equals(ticket.path("status").asString(""))) {
                    tickets.add(new Ticket(ticket.path("id").asString(null), null));
                } else {
                    tickets.add(new Ticket(null, ticket.path("details").path("error").asString(
                            ticket.path("message").asString("error"))));
                }
            }
        }
        return tickets;
    }

    /** @return ticketId -> hata kodu (basarili makbuzlar icin null) */
    public Map<String, String> receipts(List<String> ticketIds) {
        Map<String, String> result = new HashMap<>();
        if (ticketIds.isEmpty()) {
            return result;
        }
        JsonNode response;
        try {
            response = http.post().uri(RECEIPTS_URL).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ids", ticketIds)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PushUnavailableException("Expo makbuzları alınamadı", e);
        }
        JsonNode data = response == null ? null : response.path("data");
        if (data != null && data.isObject()) {
            for (var entry : data.properties()) {
                JsonNode receipt = entry.getValue();
                result.put(entry.getKey(), "ok".equals(receipt.path("status").asString(""))
                        ? null
                        : receipt.path("details").path("error").asString("error"));
            }
        }
        return result;
    }
}
