package app.folyo.card;

import app.folyo.auth.FolyoPrincipal;
import app.folyo.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/cards")
public class CardController {

    private final CardService cardService;
    private final MediaService mediaService;

    public CardController(CardService cardService, MediaService mediaService) {
        this.cardService = cardService;
        this.mediaService = mediaService;
    }

    public record CreateRequest(@NotNull(message = "Kart tipini seç") CardType type, CardSize size,
            Map<String, Object> config) {
    }

    public record UpdateRequest(CardSize size, Boolean visible, Map<String, Object> config) {
    }

    public record ReorderRequest(@NotNull List<UUID> cardIds) {
    }

    @GetMapping
    public List<CardView> list(@AuthenticationPrincipal FolyoPrincipal principal) {
        List<Card> cards = cardService.listForOwner(principal.userId());
        return CardView.fromAll(cards, mediaService.viewsByCard(cards));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardView create(@AuthenticationPrincipal FolyoPrincipal principal, @Valid @RequestBody CreateRequest body) {
        return CardView.from(cardService.create(principal.userId(), body.type(), body.size(), body.config()));
    }

    @PatchMapping("/{cardId}")
    public CardView update(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable UUID cardId,
            @RequestBody UpdateRequest body) {
        var changes = new CardService.CardChanges(body.size(), body.visible(), body.config());
        Card card = cardService.update(principal.userId(), cardId, changes);
        return CardView.from(card, mediaService.viewsByCard(List.of(card)));
    }

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal FolyoPrincipal principal, @PathVariable UUID cardId) {
        cardService.delete(principal.userId(), cardId);
    }

    @PutMapping("/order")
    public List<CardView> reorder(@AuthenticationPrincipal FolyoPrincipal principal,
            @Valid @RequestBody ReorderRequest body) {
        List<Card> cards = cardService.reorder(principal.userId(), body.cardIds());
        return CardView.fromAll(cards, mediaService.viewsByCard(cards));
    }
}
