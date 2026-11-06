package app.folyo.connection;

import app.folyo.auth.FolyoPrincipal;
import app.folyo.config.FolyoProperties;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me/connections")
public class ConnectionController {

    private final ConnectionService connectionService;
    private final FolyoProperties properties;

    public ConnectionController(ConnectionService connectionService, FolyoProperties properties) {
        this.connectionService = connectionService;
        this.properties = properties;
    }

    @GetMapping
    public List<ConnectionService.ConnectionView> list(@AuthenticationPrincipal FolyoPrincipal principal) {
        return connectionService.list(principal.userId());
    }

    /** Tarayici bu adrese gider; saglayicinin izin sayfasina yonlendirilir. */
    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable String provider,
            HttpSession session) {
        String url = connectionService.start(principal.userId(), provider(provider), session);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@AuthenticationPrincipal FolyoPrincipal principal,
            @PathVariable String provider, @RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            HttpSession session) {
        ConnectionProvider target = provider(provider);
        String result;
        if (error != null) {
            // Kullanici izin vermedi; state yine tuketilir.
            session.removeAttribute(ConnectionService.PENDING_ATTRIBUTE);
            result = "connectError=" + target.slug();
        } else {
            connectionService.complete(principal.userId(), target, code, state, session);
            result = "connected=" + target.slug();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(properties.webUrl() + "/edit?" + result))
                .build();
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable String provider) {
        connectionService.disconnect(principal.userId(), provider(provider));
    }

    private static ConnectionProvider provider(String slug) {
        return ConnectionProvider.fromSlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bilinmeyen bağlantı"));
    }
}
