package app.folyo.profile;

import app.folyo.auth.FolyoPrincipal;
import app.folyo.auth.User;
import app.folyo.auth.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final ProfileService profileService;
    private final UserRepository users;

    public MeController(ProfileService profileService, UserRepository users) {
        this.profileService = profileService;
        this.users = users;
    }

    public record UserView(UUID id, String name, String email, String avatarUrl) {
    }

    public record ProfileView(UUID id, String username, String displayName, String bio, String location,
            String timezone, String status, ProfileVisibility visibility) {

        static ProfileView from(Profile p) {
            return new ProfileView(p.getId(), p.getUsername(), p.getDisplayName(), p.getBio(), p.getLocation(),
                    p.getTimezone(), p.getStatus(), p.getVisibility());
        }
    }

    public record MeView(UserView user, ProfileView profile) {
    }

    public record ClaimRequest(
            @NotBlank(message = "Bir kullanıcı adı yaz") String username,
            @NotBlank(message = "Görünen adını yaz") @Size(max = 60, message = "Görünen ad en fazla 60 karakter") String displayName) {
    }

    public record UpdateRequest(
            @NotBlank(message = "Görünen adını yaz") @Size(max = 60, message = "Görünen ad en fazla 60 karakter") String displayName,
            @Size(max = 280, message = "Biyografi en fazla 280 karakter") String bio,
            @Size(max = 60, message = "Konum en fazla 60 karakter") String location,
            @Size(max = 64) String timezone,
            @Size(max = 40, message = "Durum en fazla 40 karakter") String status,
            ProfileVisibility visibility) {
    }

    @GetMapping
    public MeView me(@AuthenticationPrincipal FolyoPrincipal principal) {
        User user = users.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        ProfileView profile = profileService.findByUserId(principal.userId()).map(ProfileView::from).orElse(null);
        return new MeView(new UserView(user.getId(), user.getName(), user.getEmail(), user.getAvatarUrl()), profile);
    }

    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView claim(@AuthenticationPrincipal FolyoPrincipal principal, @Valid @RequestBody ClaimRequest body) {
        return ProfileView.from(profileService.claim(principal.userId(), body.username(), body.displayName()));
    }

    @PatchMapping("/profile")
    public ProfileView update(@AuthenticationPrincipal FolyoPrincipal principal,
            @Valid @RequestBody UpdateRequest body) {
        var details = new ProfileService.ProfileDetails(body.displayName(), body.bio(), body.location(),
                body.timezone(), body.status(), body.visibility());
        return ProfileView.from(profileService.update(principal.userId(), details));
    }
}
