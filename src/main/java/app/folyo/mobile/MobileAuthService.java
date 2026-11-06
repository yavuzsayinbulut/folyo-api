package app.folyo.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mobil giris: web'de oturumu acik kullanici, uygulamanin urettigi PKCE challenge'i icin 2 dakikalik tek kullanimlik
 * kod alir; uygulama kodu kendi sakladigi verifier ile token'a cevirir. Kod baska biri tarafindan yakalansa bile
 * verifier olmadan ise yaramaz.
 */
@Service
public class MobileAuthService {

    static final Duration CODE_TTL = Duration.ofMinutes(2);
    static final Duration TOKEN_TTL = Duration.ofDays(90);
    private static final Duration TOUCH_INTERVAL = Duration.ofHours(1);
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private final MobileAuthRepository repository;
    private final List<String> redirectPrefixes;
    private final SecureRandom random = new SecureRandom();

    public MobileAuthService(MobileAuthRepository repository,
            @Value("${folyo.mobile.redirect-prefixes:folyo://}") List<String> redirectPrefixes) {
        this.repository = repository;
        this.redirectPrefixes = redirectPrefixes;
    }

    public String issueCode(UUID userId, String codeChallenge, String redirectUri) {
        if (codeChallenge == null || !CHALLENGE.matcher(codeChallenge).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Geçersiz giriş isteği");
        }
        if (!redirectAllowed(redirectUri)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bu uygulama adresine yönlendirme yapılamaz");
        }
        String code = randomToken();
        repository.saveCode(sha256(code), userId, codeChallenge, redirectUri, Instant.now().plus(CODE_TTL));
        return code;
    }

    public IssuedToken exchange(String code, String codeVerifier, String deviceName) {
        if (code == null || codeVerifier == null || !VERIFIER.matcher(codeVerifier).matches()) {
            throw invalidGrant();
        }
        MobileAuthRepository.PendingCode pending = repository.consumeCode(sha256(code)).orElseThrow(this::invalidGrant);
        if (Instant.now().isAfter(pending.expiresAt())) {
            throw invalidGrant();
        }
        // PKCE S256: BASE64URL(SHA-256(verifier)) == challenge
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(codeVerifier));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                pending.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
            throw invalidGrant();
        }
        String token = randomToken();
        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        String name = deviceName == null || deviceName.isBlank() ? "Mobil uygulama" : deviceName.strip();
        repository.saveToken(UUID.randomUUID(), pending.userId(), sha256(token),
                name.length() > 60 ? name.substring(0, 60) : name, expiresAt);
        return new IssuedToken(token, expiresAt);
    }

    /** Bearer token'i dogrular; son kullanim zamani saatte en fazla bir kez yazilir. */
    public Optional<MobileAuthRepository.ActiveToken> authenticate(String token) {
        Optional<MobileAuthRepository.ActiveToken> active = repository.findActive(sha256(token));
        active.filter(t -> t.lastUsedAt() == null || t.lastUsedAt().isBefore(Instant.now().minus(TOUCH_INTERVAL)))
                .ifPresent(t -> repository.touch(t.tokenId()));
        return active;
    }

    public void revoke(UUID tokenId, UUID userId) {
        repository.revoke(tokenId, userId);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void cleanupCodes() {
        repository.deleteExpiredCodes();
    }

    private boolean redirectAllowed(String redirectUri) {
        return redirectUri != null && redirectUri.length() <= 500
                && redirectPrefixes.stream().anyMatch(prefix -> !prefix.isBlank() && redirectUri.startsWith(prefix));
    }

    private ResponseStatusException invalidGrant() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giriş kodu geçersiz ya da süresi dolmuş");
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
