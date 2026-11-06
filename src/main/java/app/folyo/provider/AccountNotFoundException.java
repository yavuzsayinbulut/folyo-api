package app.folyo.provider;

/** Kart config'indeki kullanici adi saglayicida yok. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
