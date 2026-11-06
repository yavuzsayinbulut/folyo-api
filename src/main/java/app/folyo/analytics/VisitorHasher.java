package app.folyo.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Ziyaretciyi saklamadan "bugun bu kisiyi gorduk mu" sorusunu cevaplar:
 * ozet = SHA-256(gunun tuzu + IP + tarayici bilgisi). Tuz ertesi gun silindigi icin ozet IP'ye geri cevrilemez
 * ve farkli gunler birbirine baglanamaz.
 */
@Component
public class VisitorHasher {

    private record DaySalt(LocalDate day, byte[] salt) {
    }

    private final AnalyticsRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<DaySalt> current = new AtomicReference<>();

    public VisitorHasher(AnalyticsRepository repository) {
        this.repository = repository;
    }

    public byte[] hash(LocalDate day, String ip, String userAgent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(saltFor(day));
            digest.update(String.valueOf(ip).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(String.valueOf(userAgent).getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] saltFor(LocalDate day) {
        DaySalt cached = current.get();
        if (cached != null && cached.day().equals(day)) {
            return cached.salt();
        }
        byte[] candidate = new byte[32];
        random.nextBytes(candidate);
        byte[] salt = repository.saltFor(day, candidate);
        current.set(new DaySalt(day, salt));
        return salt;
    }
}
