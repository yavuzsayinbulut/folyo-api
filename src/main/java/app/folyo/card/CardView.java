package app.folyo.card;

import app.folyo.media.MediaService.MediaView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** media yalniz galeri kartlarinda doludur. */
public record CardView(UUID id, CardType type, CardSize size, boolean visible, Map<String, Object> config,
        List<MediaView> media) {

    public static CardView from(Card card) {
        return from(card, Map.of());
    }

    public static CardView from(Card card, Map<UUID, List<MediaView>> mediaByCard) {
        List<MediaView> media = card.getType() == CardType.GALLERY
                ? mediaByCard.getOrDefault(card.getId(), List.of())
                : null;
        return new CardView(card.getId(), card.getType(), card.getSize(), card.isVisible(), card.getConfig(), media);
    }

    public static List<CardView> fromAll(List<Card> cards, Map<UUID, List<MediaView>> mediaByCard) {
        return cards.stream().map(card -> from(card, mediaByCard)).toList();
    }
}
