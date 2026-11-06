package app.folyo.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Testler icin EXIF (yon + sahte GPS notu) iceren JPEG uretir. */
public final class PhotoFixtures {

    private PhotoFixtures() {
    }

    public static byte[] jpegWithExifOrientation(int width, int height, int orientation) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width / 2, height);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        byte[] jpeg = out.toByteArray();

        // Minimal EXIF: big-endian TIFF, tek IFD girdisi (0x0112 Orientation), ardindan "GPS" iceren bir not.
        byte[] note = "GPS 41.0082N 28.9784E".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + note.length);
        tiff.put(new byte[] {'M', 'M', 0, 42}).putInt(8).putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0).put(note);
        byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int segmentLength = 2 + exifHeader.length + tiff.capacity();
        ByteBuffer result = ByteBuffer.allocate(jpeg.length + 2 + segmentLength);
        result.put(jpeg, 0, 2)
                .put((byte) 0xFF).put((byte) 0xE1).putShort((short) segmentLength)
                .put(exifHeader).put(tiff.array())
                .put(jpeg, 2, jpeg.length - 2);
        return result.array();
    }
}
