package app.folyo.notification;

import app.folyo.analytics.AnalyticsService;
import app.folyo.auth.FolyoPrincipal;
import app.folyo.guestbook.GuestbookEntryView;
import app.folyo.guestbook.GuestbookService;
import app.folyo.mobile.BearerTokenFilter;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final NotificationService notifications;
    private final AnalyticsService analytics;
    private final ProfileService profileService;
    private final GuestbookService guestbook;

    public NotificationController(NotificationService notifications, AnalyticsService analytics,
            ProfileService profileService, GuestbookService guestbook) {
        this.notifications = notifications;
        this.analytics = analytics;
        this.profileService = profileService;
        this.guestbook = guestbook;
    }

    public record DeviceRequest(@NotBlank String expoToken, @NotBlank String platform) {
    }

    public record LatestMessage(String authorName, String body, Instant createdAt) {
    }

    /** Ana ekran widget'inin tek istekte ihtiyac duydugu ozet. */
    public record WidgetSummary(String username, String displayName, int weekVisitors, int previousWeekVisitors,
            List<Integer> last7Days, LatestMessage latestMessage) {
    }

    /** Uygulama bildirim izni alininca cihazini kaydeder; bearer token'in oturumuna baglanir. */
    @PostMapping("/api/me/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@AuthenticationPrincipal FolyoPrincipal principal, @Valid @RequestBody DeviceRequest body,
            HttpServletRequest request) {
        Object tokenId = request.getAttribute(BearerTokenFilter.TOKEN_ID_ATTRIBUTE);
        notifications.registerDevice(principal.userId(), tokenId instanceof UUID id ? id : null, body.expoToken(),
                body.platform());
    }

    @DeleteMapping("/api/me/devices/{expoToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable String expoToken) {
        notifications.unregisterDevice(principal.userId(), expoToken);
    }

    @GetMapping("/api/me/notification-settings")
    public NotificationRepository.Settings settings(@AuthenticationPrincipal FolyoPrincipal principal) {
        return notifications.settings(principal.userId());
    }

    @PutMapping("/api/me/notification-settings")
    public NotificationRepository.Settings saveSettings(@AuthenticationPrincipal FolyoPrincipal principal,
            @RequestBody NotificationRepository.Settings body) {
        return notifications.saveSettings(principal.userId(), body);
    }

    @GetMapping("/api/me/widget")
    public WidgetSummary widget(@AuthenticationPrincipal FolyoPrincipal principal) {
        Profile profile = profileService.requireByUserId(principal.userId());
        AnalyticsService.StatsView stats = analytics.stats(principal.userId(), 7);
        List<GuestbookEntryView> entries = guestbook.entriesForOwner(principal.userId());
        LatestMessage latest = entries.stream()
                .filter(e -> e.status() == app.folyo.guestbook.GuestbookStatus.VISIBLE)
                .findFirst()
                .map(e -> new LatestMessage(e.authorName(), e.body(), e.createdAt()))
                .orElse(null);
        return new WidgetSummary(profile.getUsername(), profile.getDisplayName(), stats.totals().visitors(),
                stats.previous().visitors(), stats.series().stream().map(AnalyticsService.DayStat::visitors).toList(),
                latest);
    }
}
