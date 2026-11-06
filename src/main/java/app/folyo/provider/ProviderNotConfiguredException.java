package app.folyo.provider;

/** Saglayicinin API bilgileri (key / client id) sunucuda tanimli degil. */
public class ProviderNotConfiguredException extends RuntimeException {

    public ProviderNotConfiguredException(String provider) {
        super(provider + " yapilandirilmamis");
    }
}
