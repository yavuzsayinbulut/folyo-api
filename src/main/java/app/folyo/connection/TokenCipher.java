package app.folyo.connection;

import app.folyo.config.FolyoProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * OAuth token'larini AES-256-GCM ile sifreler. Bicim: [surum 1 bayt][IV 12 bayt][sifreli metin + 16 bayt etiket].
 * GCM etiketi sayesinde degistirilmis veri cozulmeye calisilinca hata verir.
 */
@Component
public class TokenCipher {

    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(FolyoProperties properties) {
        if (properties.tokenKey() == null || properties.tokenKey().isBlank()) {
            throw new IllegalStateException("folyo.token-key (FOLYO_TOKEN_KEY) tanimli degil");
        }
        byte[] raw = Base64.getDecoder().decode(properties.tokenKey());
        if (raw.length != 32) {
            throw new IllegalStateException("folyo.token-key 32 bayt olmali (base64), su an " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length).put(VERSION).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token sifrelenemedi", e);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored == null || stored.length <= 1 + IV_LENGTH || stored[0] != VERSION) {
            throw new IllegalArgumentException("Token bicimi taninmadi");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, IV_LENGTH));
            byte[] plain = cipher.doFinal(stored, 1 + IV_LENGTH, stored.length - 1 - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token cozulemedi (anahtar degismis ya da veri bozulmus)", e);
        }
    }
}
