package app.folyo.provider;

/** Saglayici yanit vermedi, limit doldu ya da beklenmeyen yanit dondu. Kart "su an alinamadi" gosterir. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
