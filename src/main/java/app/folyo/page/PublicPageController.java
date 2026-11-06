package app.folyo.page;

import app.folyo.auth.User;
import app.folyo.auth.UserRepository;
import app.folyo.card.Card;
import app.folyo.card.CardService;
import app.folyo.card.CardView;
import app.folyo.media.MediaService;
import app.folyo.profile.Profile;
import app.folyo.profile.ProfileService;
import app.folyo.profile.ProfileVisibility;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Ziyaretcinin gordugu sayfanin verisi. Giris gerektirmez; gizli kartlar donmez. */
@RestController
public class PublicPageController {

    private final ProfileService profileService;
    private final CardService cardService;
    private final UserRepository users;
    private final MediaService mediaService;

    public PublicPageController(ProfileService profileService, CardService cardService, UserRepository users,
            MediaService mediaService) {
        this.profileService = profileService;
        this.cardService = cardService;
        this.users = users;
        this.mediaService = mediaService;
    }

    public record PublicPage(String username, String displayName, String bio, String location, String timezone,
            String status, String avatarUrl, boolean indexable, List<CardView> cards) {
    }

    @GetMapping("/api/public/profiles/{username}")
    public PublicPage page(@PathVariable String username) {
        Profile profile = profileService.requireByUsername(username);
        String avatarUrl = users.findById(profile.getUserId()).map(User::getAvatarUrl).orElse(null);
        List<Card> visible = cardService.visibleCards(profile.getId());
        List<CardView> cards = CardView.fromAll(visible, mediaService.viewsByCard(visible));
        return new PublicPage(profile.getUsername(), profile.getDisplayName(), profile.getBio(),
                profile.getLocation(), profile.getTimezone(), profile.getStatus(), avatarUrl,
                profile.getVisibility() == ProfileVisibility.PUBLIC, cards);
    }

    @GetMapping("/api/usernames/{username}/availability")
    public ProfileService.Availability availability(@PathVariable String username) {
        return profileService.availability(username);
    }
}
