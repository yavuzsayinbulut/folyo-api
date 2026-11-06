package app.folyo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yalniz "dev" profilinde vardir: OAuth uygulamasi kurmadan lokal gelistirme ve test icin giris.
 * Prod'da bu bean olusmaz, endpoint 404 doner.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevLoginController {

    private final UserService userService;
    private final PrincipalSession principalSession;

    public DevLoginController(UserService userService, PrincipalSession principalSession) {
        this.userService = userService;
        this.principalSession = principalSession;
    }

    public record DevLoginRequest(@NotBlank(message = "Bir isim yaz") @Size(max = 60) String name) {
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody DevLoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String name = body.name().strip();
        String subject = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        User user = userService.signIn("dev", subject, null, name, null);
        principalSession.signIn(user, request, response);
        return ResponseEntity.noContent().build();
    }
}
