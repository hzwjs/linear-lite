package com.linearlite.server.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 文档图片元数据提取与缩略图生成。
 *
 * <p>只用 JDK ImageIO 能解码的位图；WebP/AVIF/HEIC/SVG 等返回 {@code null}，由调用方按“原图链路”处理。
 * 像素总量或边长超限时仍返回尺寸但不生成缩略图，避免在请求线程里解码超大图。
 */
@Service
public class DocumentImageProcessor {

    /** 缩略图最长边。 */
    static final int MAX_THUMBNAIL_EDGE = 512;
    /** 单张图片允许解码的最大像素总量（约 8000x8000）。 */
    static final long MAX_DECODE_PIXELS = 64_000_000L;

    public record ImageMetadata(
            int width, int height, String sourceContentType, String thumbnailContentType, byte[] thumbnail) {
        public boolean hasThumbnail() {
            return thumbnail != null && thumbnail.length > 0;
        }
    }

    /**
     * @return 可解码位图的元数据；内容不是 ImageIO 可解码的位图时返回 {@code null}。
     */
    public ImageMetadata analyze(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        return analyzeStream(new ByteArrayInputStream(content));
    }

    /** 输入流由调用方管理；ImageIO 使用磁盘缓存处理大文件，避免复制完整附件到堆。 */
    public ImageMetadata analyzeStream(InputStream content) {
        if (content == null) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(content)) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                String sourceContentType = toContentType(reader.getFormatName());
                if ((long) width * height > MAX_DECODE_PIXELS) {
                    return new ImageMetadata(width, height, sourceContentType, null, null);
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    return null;
                }
                return withThumbnail(width, height, sourceContentType, image);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private ImageMetadata withThumbnail(int width, int height, String sourceContentType, BufferedImage image) {
        boolean alpha = image.getColorModel().hasAlpha();
        double scale = Math.min(1.0, (double) MAX_THUMBNAIL_EDGE / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage target = new BufferedImage(
                targetWidth, targetHeight, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
            }
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(target, alpha ? "png" : "jpeg", out)) {
                return new ImageMetadata(width, height, sourceContentType, null, null);
            }
        } catch (IOException e) {
            return new ImageMetadata(width, height, sourceContentType, null, null);
        }
        return new ImageMetadata(width, height, sourceContentType, alpha ? "image/png" : "image/jpeg", out.toByteArray());
    }

    private static String toContentType(String formatName) {
        if (formatName == null || formatName.isBlank()) {
            return null;
        }
        return switch (formatName.toLowerCase(java.util.Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "wbmp" -> "image/vnd.wap.wbmp";
            default -> "image/" + formatName.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
