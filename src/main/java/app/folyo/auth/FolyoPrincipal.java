package app.folyo.auth;

import java.io.Serializable;
import java.util.UUID;

/** Oturumdaki kullanici. Hangi yolla girilmis olursa olsun (Google, GitHub, dev) uygulama bu tipi gorur. */
public record FolyoPrincipal(UUID userId, String name) implements Serializable {
}
