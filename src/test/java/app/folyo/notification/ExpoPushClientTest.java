package app.folyo.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ExpoPushClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ExpoPushClient client = new ExpoPushClient(builder, "");

    @Test
    void mapsTicketsInOrderAndSplitsInto100MessageChunks() {
        StringBuilder ok = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 100; i++) {
            ok.append(i == 0 ? "" : ",").append("{\"status\":\"ok\",\"id\":\"t").append(i).append("\"}");
        }
        ok.append("]}");
        server.expect(requestTo(ExpoPushClient.SEND_URL)).andRespond(withSuccess(ok.toString(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ExpoPushClient.SEND_URL)).andRespond(withSuccess("""
                {"data":[{"status":"error","message":"x","details":{"error":"DeviceNotRegistered"}}]}""",
                MediaType.APPLICATION_JSON));

        List<ExpoPushClient.Message> messages = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            messages.add(new ExpoPushClient.Message("ExponentPushToken[" + i + "]", "Başlık", "Gövde", Map.of()));
        }
        List<ExpoPushClient.Ticket> tickets = client.send(messages);

        assertThat(tickets).hasSize(101);
        assertThat(tickets.get(0).ticketId()).isEqualTo("t0");
        assertThat(tickets.get(100).deviceNotRegistered()).isTrue();
        server.verify();
    }

    @Test
    void serverErrorsBecomeRetryableAndReceiptsAreParsed() {
        server.expect(times(1), requestTo(ExpoPushClient.SEND_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client.send(List.of(new ExpoPushClient.Message("ExponentPushToken[a]", "t", "b", Map.of()))))
                .isInstanceOf(ExpoPushClient.PushUnavailableException.class);

        server.reset();
        server.expect(requestTo(ExpoPushClient.RECEIPTS_URL)).andRespond(withSuccess("""
                {"data":{"r1":{"status":"ok"},"r2":{"status":"error","details":{"error":"DeviceNotRegistered"}}}}""",
                MediaType.APPLICATION_JSON));
        Map<String, String> receipts = client.receipts(List.of("r1", "r2", "r3"));
        assertThat(receipts).containsEntry("r1", null).containsEntry("r2", "DeviceNotRegistered").doesNotContainKey("r3");
    }
}
