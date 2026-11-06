package app.folyo.profile;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    public record Availability(boolean available, String reason) {
    }

    public record ProfileDetails(String displayName, String bio, String location, String timezone, String status,
            ProfileVisibility visibility) {
    }

    @Transactional(readOnly = true)
    public Availability availability(String rawUsername) {
        String username = UsernamePolicy.normalize(rawUsername);
        Optional<String> violation = UsernamePolicy.violation(username);
        if (violation.isPresent()) {
            return new Availability(false, violation.get());
        }
        if (profiles.existsByUsername(username)) {
            return new Availability(false, "Bu kullanıcı adı alınmış");
        }
        return new Availability(true, null);
    }

    /**
     * Kullanici adini alir. On kontroller kullaniciya net mesaj vermek icindir; es zamanli iki istekte
     * asil garanti uq_profiles_username / uq_profiles_user kisitlaridir (ApiErrorHandler 409'a cevirir).
     */
    @Transactional
    public Profile claim(UUID userId, String rawUsername, String displayName) {
        String username = UsernamePolicy.normalize(rawUsername);
        UsernamePolicy.violation(username).ifPresent(reason -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        });
        if (profiles.existsByUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zaten bir sayfan var");
        }
        if (profiles.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bu kullanıcı adı alınmış");
        }
        return profiles.saveAndFlush(new Profile(userId, username, displayName.strip()));
    }

    @Transactional
    public Profile update(UUID userId, ProfileDetails details) {
        Profile profile = requireByUserId(userId);
        profile.updateDetails(details.displayName().strip(), blankToNull(details.bio()),
                blankToNull(details.location()), validTimezone(details.timezone()), blankToNull(details.status()),
                details.visibility() == null ? ProfileVisibility.PUBLIC : details.visibility());
        return profile;
    }

    @Transactional(readOnly = true)
    public Optional<Profile> findByUserId(UUID userId) {
        return profiles.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Profile requireByUserId(UUID userId) {
        return profiles.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Önce bir sayfa aç"));
    }

    @Transactional(readOnly = true)
    public Profile requireByUsername(String rawUsername) {
        return profiles.findByUsername(UsernamePolicy.normalize(rawUsername))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Böyle bir sayfa yok"));
    }

    /** Ayni sayfadaki degisiklikleri siraya sokar; cagiran transaction icinde olmalidir. */
    public Profile lockByUserId(UUID userId) {
        return profiles.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Önce bir sayfa aç"));
    }

    private static String validTimezone(String timezone) {
        String value = blankToNull(timezone);
        if (value == null) {
            return null;
        }
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Geçersiz saat dilimi");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
