package app.folyo.provider;

/** Kullanici saglayicida izni geri almis; refresh token artik gecersiz. Baglanti yeniden kurulmalidir. */
public class AuthorizationRevokedException extends RuntimeException {

    public AuthorizationRevokedException(String message) {
        super(message);
    }
}
