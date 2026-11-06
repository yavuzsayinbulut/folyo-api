package app.folyo.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Yuklenen fotografi herkese acik sunulabilir hale getirir: EXIF yonunu uygular, uzun kenari 2048 px'e indirir ve
 * yeniden kodlar. Yeniden kodlama GPS konumu dahil butun metadata'yi siler.
 */
@Component
public class PhotoNormalizer {

    static final int MAX_EDGE = 2048;
    private static final long MAX_SOURCE_PIXELS = 50_000_000L;
    private static final float JPEG_QUALITY = 0.85f;

    public record NormalizedPhoto(byte[] bytes, String contentType, int width, int height) {
    }

    public NormalizedPhoto normalize(byte[] input) {
        String contentType = detectType(input);
        BufferedImage image = decode(input);
        if ("image/jpeg".equals(contentType)) {
            image = applyOrientation(image, orientation(input));
        }
        image = fitWithin(image, MAX_EDGE, "image/png".equals(contentType));
        byte[] output = "image/jpeg".equals(contentType) ? encodeJpeg(image) : encodePng(image);
        return new NormalizedPhoto(output, contentType, image.getWidth(), image.getHeight());
    }

    /** Uzantiya ya da istemcinin bildirdigi tipe degil, dosyanin ilk baytlarina bakilir. */
    static String detectType(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (bytes.length > png.length) {
            boolean match = true;
            for (int i = 0; i < png.length; i++) {
                match &= bytes[i] == png[i];
            }
            if (match) {
                return "image/png";
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yalnız JPEG veya PNG yükleyebilirsin");
    }

    /** Boyut, piksel verisi acilmadan basliktan okunur; dev gorsellerle bellek doldurulmasi engellenir. */
    private static BufferedImage decode(byte[] input) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unreadable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_SOURCE_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Görsel çözünürlüğü çok yüksek");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ResponseStatusException status) {
                throw status;
            }
            throw unreadable();
        }
    }

    private static int orientation(byte[] input) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // Bozuk ya da eksik EXIF: fotograf oldugu gibi kullanilir.
        }
        return 1;
    }

    /** Telefonlarin cogu fotografi yan cekip EXIF'e "dondur" notu dusur; metadata silinmeden once donus uygulanir. */
    static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        int quarterTurns = switch (orientation) {
            case 3, 4 -> 2;
            case 6, 5 -> 1;
            case 8, 7 -> 3;
            default -> 0;
        };
        if (quarterTurns == 0) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = quarterTurns % 2 == 1;
        BufferedImage rotated = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        try {
            g.translate(rotated.getWidth() / 2.0, rotated.getHeight() / 2.0);
            g.rotate(Math.toRadians(90.0 * quarterTurns));
            g.translate(-w / 2.0, -h / 2.0);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }

    private static BufferedImage fitWithin(BufferedImage source, int maxEdge, boolean keepAlpha) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        int type = keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (scale == 1.0 && source.getType() == type) {
            return source;
        }
        BufferedImage target = new BufferedImage(targetW, targetH, type);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("JPEG yazilamadi", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] encodePng(BufferedImage image) {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("PNG yazilamadi", e);
        }
        return out.toByteArray();
    }

    private static ResponseStatusException unreadable() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bu görsel okunamadı");
    }
}
