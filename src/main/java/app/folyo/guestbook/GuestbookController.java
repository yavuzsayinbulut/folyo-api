package app.folyo.guestbook;

import app.folyo.auth.FolyoPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestbookController {

    private final GuestbookService guestbookService;

    public GuestbookController(GuestbookService guestbookService) {
        this.guestbookService = guestbookService;
    }

    public record WriteRequest(
            @NotBlank(message = "Önce bir mesaj yaz") @Size(max = 280, message = "Mesaj en fazla 280 karakter") String body) {
    }

    public record StatusRequest(@NotNull GuestbookStatus status) {
    }

    @GetMapping("/api/public/profiles/{username}/guestbook")
    public List<GuestbookEntryView> visible(@PathVariable String username) {
        return guestbookService.visibleEntries(username);
    }

    @PostMapping("/api/profiles/{username}/guestbook")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> write(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable String username,
            @Valid @RequestBody WriteRequest body) {
        return Map.of("id", guestbookService.write(username, principal.userId(), body.body()));
    }

    @GetMapping("/api/me/guestbook")
    public List<GuestbookEntryView> mine(@AuthenticationPrincipal FolyoPrincipal principal) {
        return guestbookService.entriesForOwner(principal.userId());
    }

    @PatchMapping("/api/me/guestbook/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable UUID entryId,
            @Valid @RequestBody StatusRequest body) {
        guestbookService.changeStatus(principal.userId(), entryId, body.status());
    }

    @DeleteMapping("/api/me/guestbook/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable UUID entryId) {
        guestbookService.delete(principal.userId(), entryId);
    }
}
