package app.folyo.mobile;

import app.folyo.auth.FolyoPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MobileAuthController {

    private final MobileAuthService mobileAuth;

    public MobileAuthController(MobileAuthService mobileAuth) {
        this.mobileAuth = mobileAuth;
    }

    public record CodeRequest(@NotBlank String codeChallenge, @NotBlank String redirectUri) {
    }

    public record TokenRequest(String code, String codeVerifier, String deviceName) {
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }

    /** Web'deki kopru sayfasi cagirir (cerezli oturum + CSRF). */
    @PostMapping("/api/me/mobile/codes")
    public Map<String, String> code(@AuthenticationPrincipal FolyoPrincipal principal,
            @Valid @RequestBody CodeRequest body) {
        return Map.of("code", mobileAuth.issueCode(principal.userId(), body.codeChallenge(), body.redirectUri()));
    }

    /** Uygulama cagirir; oturumu yoktur, kod + verifier kimlik yerine gecer. */
    @PostMapping("/api/mobile/token")
    public TokenResponse token(@RequestBody TokenRequest body) {
        var issued = mobileAuth.exchange(body.code(), body.codeVerifier(), body.deviceName());
        return new TokenResponse(issued.token(), issued.expiresAt());
    }

    /** Uygulamadan cikis: yalniz bu istegi yapan token iptal edilir. */
    @DeleteMapping("/api/me/mobile/token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal FolyoPrincipal principal, HttpServletRequest request) {
        Object tokenId = request.getAttribute(BearerTokenFilter.TOKEN_ID_ATTRIBUTE);
        if (!(tokenId instanceof UUID id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bu istek bir mobil token ile yapılmadı");
        }
        mobileAuth.revoke(id, principal.userId());
    }
}
