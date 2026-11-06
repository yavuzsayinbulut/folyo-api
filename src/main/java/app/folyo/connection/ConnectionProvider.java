package app.folyo.connection;

import java.util.Locale;
import java.util.Optional;

/** Kullanicinin kendi hesabina erisim izni gereken saglayicilar. */
public enum ConnectionProvider {
    SPOTIFY,
    STRAVA;

    public String slug() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ConnectionProvider> fromSlug(String slug) {
        for (ConnectionProvider provider : values()) {
            if (provider.slug().equals(slug)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
