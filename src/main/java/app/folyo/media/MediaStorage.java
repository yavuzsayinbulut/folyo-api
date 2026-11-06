package app.folyo.media;

import app.folyo.config.FolyoProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Fotograflari yerel diskte tutar (folyo.media-dir). Prod'da nesne depolamaya (S3 vb.) gecilecek tek yer burasi. */
@Component
public class MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);
    private static final Pattern KEY = Pattern.compile("^[0-9a-f-]{36}\\.(jpg|png)$");

    private final Path root;

    public MediaStorage(FolyoProperties properties) {
        this.root = Path.of(properties.mediaDir()).toAbsolutePath().normalize();
    }

    /** Once gecici dosyaya yazilir, sonra tek hamlede tasinir; yarim dosya sunulmaz. */
    public void write(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, "upload-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Fotograf kaydedilemedi", e);
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Fotograf okunamadi", e);
        }
    }

    public void deleteQuietly(Collection<String> keys) {
        for (String key : keys) {
            try {
                Files.deleteIfExists(resolve(key));
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Fotograf dosyasi silinemedi: {}", key, e);
            }
        }
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Gecersiz depolama anahtari");
        }
        return root.resolve(key);
    }
}
