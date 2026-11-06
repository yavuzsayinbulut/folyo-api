package app.folyo.connection;

/** Sayfada bu saglayicinin aktif baglantisi yok (hic kurulmamis ya da izin geri alinmis). */
public class ConnectionMissingException extends RuntimeException {

    public ConnectionMissingException(ConnectionProvider provider) {
        super(provider.slug() + " bağlı değil");
    }
}
