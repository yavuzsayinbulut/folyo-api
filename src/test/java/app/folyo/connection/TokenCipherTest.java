package app.folyo.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.folyo.config.FolyoProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static FolyoProperties props(String key) {
        return new FolyoProperties("http://localhost", key, "/tmp", null, null, null, null, null);
    }

    private final TokenCipher cipher = new TokenCipher(props(Base64.getEncoder().encodeToString(new byte[32])));

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        byte[] stored = cipher.encrypt("gizli-erisim-token");
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("gizli-erisim-token");
        assertThat(cipher.decrypt(stored)).isEqualTo("gizli-erisim-token");
        // Her sifrelemede yeni IV: ayni token iki kez ayni bayt dizisini uretmez.
        assertThat(cipher.encrypt("gizli-erisim-token")).isNotEqualTo(stored);
    }

    @Test
    void rejectsTamperedDataAndWrongKey() {
        byte[] stored = cipher.encrypt("token");
        stored[stored.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(stored)).isInstanceOf(IllegalStateException.class);

        byte[] other = new byte[32];
        other[0] = 7;
        TokenCipher otherKey = new TokenCipher(props(Base64.getEncoder().encodeToString(other)));
        assertThatThrownBy(() -> otherKey.decrypt(cipher.encrypt("token"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutValidKey() {
        assertThatThrownBy(() -> new TokenCipher(props(null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(props(Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class);
    }
}
