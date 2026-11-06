package app.folyo.media;

import app.folyo.card.Card;
import app.folyo.card.CardRepository;
import app.folyo.card.CardType;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Galeri fotograflari. Dosya ile DB satiri ayri sistemlerde oldugu icin sira bilincli:
 * yuklemede once dosya yazilir, DB basarisizsa dosya silinir; silmede once DB commit edilir, sonra dosya silinir.
 * En kotu durumda diskte sahipsiz dosya kalir, ama DB'de dosyasi olmayan kayit kalmaz.
 */
@Service
public class MediaService {

    static final int MAX_PHOTOS_PER_GALLERY = 12;

    public record MediaView(UUID id, String url, Integer width, Integer height) {

        static MediaView from(Media media) {
            return new MediaView(media.getId(), "/api/public/media/" + media.getId(), media.getWidth(),
                    media.getHeight());
        }
    }

    public record StoredFile(byte[] bytes, String contentType) {
    }

    private final MediaRepository media;
    private final CardRepository cards;
    private final ProfileService profileService;
    private final PhotoNormalizer normalizer;
    private final MediaStorage storage;
    private final TransactionTemplate transaction;

    public MediaService(MediaRepository media, CardRepository cards, ProfileService profileService,
            PhotoNormalizer normalizer, MediaStorage storage, TransactionTemplate transaction) {
        this.media = media;
        this.cards = cards;
        this.profileService = profileService;
        this.normalizer = normalizer;
        this.storage = storage;
        this.transaction = transaction;
    }

    public MediaView upload(UUID userId, UUID cardId, byte[] upload) {
        // Ucuz kontroller once: yanlis karta yuklemede gorsel islenmez.
        Profile owner = profileService.requireByUserId(userId);
        requireGallery(cardId, owner.getId());

        PhotoNormalizer.NormalizedPhoto photo = normalizer.normalize(upload);
        String key = UUID.randomUUID() + ("image/png".equals(photo.contentType()) ? ".png" : ".jpg");
        storage.write(key, photo.bytes());
        try {
            Media saved = transaction.execute(status -> {
                Profile profile = profileService.lockByUserId(userId);
                requireGallery(cardId, profile.getId());
                if (media.countByCardId(cardId) >= MAX_PHOTOS_PER_GALLERY) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Bir galeride en fazla " + MAX_PHOTOS_PER_GALLERY + " fotoğraf olabilir");
                }
                int position = media.findMaxPosition(cardId) + 1;
                return media.save(new Media(cardId, key, photo.contentType(), photo.bytes().length, photo.width(),
                        photo.height(), position));
            });
            return MediaView.from(saved);
        } catch (RuntimeException e) {
            storage.deleteQuietly(List.of(key));
            throw e;
        }
    }

    public void delete(UUID userId, UUID mediaId) {
        String key = transaction.execute(status -> {
            Profile profile = profileService.lockByUserId(userId);
            Media item = media.findById(mediaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fotoğraf bulunamadı"));
            requireGallery(item.getCardId(), profile.getId());
            media.delete(item);
            return item.getStorageKey();
        });
        storage.deleteQuietly(List.of(key));
    }

    /** Kart listelerinde galeri fotograflarini tek sorguda getirir. */
    public Map<UUID, List<MediaView>> viewsByCard(List<Card> cardList) {
        List<UUID> galleryIds = cardList.stream().filter(c -> c.getType() == CardType.GALLERY).map(Card::getId)
                .toList();
        Map<UUID, List<MediaView>> result = new LinkedHashMap<>();
        if (galleryIds.isEmpty()) {
            return result;
        }
        galleryIds.forEach(id -> result.put(id, new java.util.ArrayList<>()));
        for (Media item : media.findByCardIdInOrderByPosition(galleryIds)) {
            result.get(item.getCardId()).add(MediaView.from(item));
        }
        return result;
    }

    /** Gizli galerinin fotografi yalniz sahibine gosterilir. */
    public Optional<StoredFile> read(UUID mediaId, UUID viewerUserId) {
        return media.findById(mediaId).flatMap(item -> cards.findById(item.getCardId())
                .filter(card -> card.isVisible() || isOwner(card, viewerUserId))
                .map(card -> new StoredFile(storage.read(item.getStorageKey()), item.getContentType())));
    }

    private boolean isOwner(Card card, UUID viewerUserId) {
        return viewerUserId != null && profileService.findByUserId(viewerUserId)
                .map(p -> p.getId().equals(card.getProfileId())).orElse(false);
    }

    private void requireGallery(UUID cardId, UUID profileId) {
        Card card = cards.findById(cardId).filter(c -> c.getProfileId().equals(profileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kart bulunamadı"));
        if (card.getType() != CardType.GALLERY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fotoğraf yalnız galeri kartına eklenir");
        }
    }
}
