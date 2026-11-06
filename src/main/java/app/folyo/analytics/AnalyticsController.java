package app.folyo.analytics;

import app.folyo.auth.FolyoPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Sayfa acilisinda tarayici gonderir (sendBeacon). Yanit govdesizdir; sayilip sayilmadigi disari soylenmez. */
    @PostMapping("/api/public/profiles/{username}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@PathVariable String username, @AuthenticationPrincipal FolyoPrincipal principal,
            HttpServletRequest request) {
        analytics.recordView(username, visitor(principal, request));
    }

    @PostMapping("/api/public/cards/{cardId}/click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void click(@PathVariable UUID cardId, @AuthenticationPrincipal FolyoPrincipal principal,
            HttpServletRequest request) {
        analytics.recordClick(cardId, visitor(principal, request));
    }

    @GetMapping("/api/me/stats")
    public AnalyticsService.StatsView stats(@AuthenticationPrincipal FolyoPrincipal principal,
            @RequestParam(defaultValue = "7") int days) {
        return analytics.stats(principal.userId(), days);
    }

    /** IP, forward-headers-strategy=framework sayesinde web proxy'sinin X-Forwarded-For bilgisinden gelir. */
    private static AnalyticsService.Visitor visitor(FolyoPrincipal principal, HttpServletRequest request) {
        return new AnalyticsService.Visitor(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT),
                principal == null ? null : principal.userId());
    }
}
