package com.linearlite.server.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentImageProcessorTest {

    private final DocumentImageProcessor processor = new DocumentImageProcessor();

    @Test
    void analyzesRasterImageAndProducesScaledThumbnail() throws Exception {
        byte[] png = pngBytes(1200, 600);

        DocumentImageProcessor.ImageMetadata metadata = processor.analyze(png);

        assertNotNull(metadata);
        assertEquals(1200, metadata.width());
        assertEquals(600, metadata.height());
        assertEquals("image/png", metadata.sourceContentType());
        assertTrue(metadata.hasThumbnail());
        assertNotNull(metadata.thumbnailContentType());
        BufferedImage thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(metadata.thumbnail()));
        assertEquals(DocumentImageProcessor.MAX_THUMBNAIL_EDGE, thumbnail.getWidth());
        assertEquals(DocumentImageProcessor.MAX_THUMBNAIL_EDGE / 2, thumbnail.getHeight());
    }

    @Test
    void returnsNullForNonImageContent() {
        assertNull(processor.analyze("not an image".getBytes()));
        assertNull(processor.analyze(new byte[0]));
        assertNull(processor.analyze(null));
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
