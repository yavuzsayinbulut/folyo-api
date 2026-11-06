package app.folyo.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PhotoNormalizerTest {

    private final PhotoNormalizer normalizer = new PhotoNormalizer();

    @Test
    void appliesExifRotationThenStripsAllMetadataAndDownscales() throws Exception {
        byte[] withExif = PhotoFixtures.jpegWithExifOrientation(3000, 1000, 6);
        assertThat(contains(withExif, "Exif")).isTrue();

        PhotoNormalizer.NormalizedPhoto photo = normalizer.normalize(withExif);

        assertThat(photo.contentType()).isEqualTo("image/jpeg");
        assertThat(contains(photo.bytes(), "Exif")).isFalse();
        assertThat(contains(photo.bytes(), "GPS")).isFalse();
        // Yan cekilmis 3000x1000 fotograf 90 derece donup 2048 uzun kenara indirildi.
        assertThat(photo.height()).isEqualTo(PhotoNormalizer.MAX_EDGE);
        assertThat(photo.width()).isLessThan(photo.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(photo.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(photo.width());
    }

    @Test
    void keepsPngAsPng() throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        PhotoNormalizer.NormalizedPhoto photo = normalizer.normalize(out.toByteArray());
        assertThat(photo.contentType()).isEqualTo("image/png");
        assertThat(photo.width()).isEqualTo(40);
    }

    @Test
    void rejectsNonImagesEvenWithImageLikeName() {
        assertThatThrownBy(() -> normalizer.normalize("merhaba".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("JPEG veya PNG");
        byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02};
        assertThatThrownBy(() -> normalizer.normalize(fakeJpeg))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("okunamadı");
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
