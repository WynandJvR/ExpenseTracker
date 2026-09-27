package com.wyn.expensetracker;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Preprocesses receipt images for better OCR accuracy.
 * Pipeline: EXIF rotation → grayscale → scale long side into [1800, 3200] px.
 */
public class ReceiptImagePreprocessor {

    public BufferedImage preprocess(File imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Unsupported image format: " + imageFile.getName()
                + " (use JPG or PNG; HEIC/WebP photos must be converted first)");
        }
        image = applyExifRotation(image, imageFile);
        image = toGrayscale(image);
        // No CLAHE / sharpening: Tesseract binarises internally, and CLAHE+sharpen amplified photo noise into
        // glyph fragments (measured: 92% -> 70% line-item recall on the synthetic receipt set).
        return fitForOcr(image);
    }

    private BufferedImage applyExifRotation(BufferedImage image, File imageFile) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);
            ExifIFD0Directory exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir == null || !exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return image;
            }

            int orientation = exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return rotateForOrientation(image, orientation);
        } catch (Exception e) {
            return image;
        }
    }

    private BufferedImage rotateForOrientation(BufferedImage image, int orientation) {
        int w = image.getWidth();
        int h = image.getHeight();

        AffineTransform transform = new AffineTransform();
        int newWidth = w;
        int newHeight = h;

        switch (orientation) {
            case 1:
                return image;
            case 3:
                transform.translate(w, h);
                transform.rotate(Math.PI);
                break;
            case 6:
                transform.translate(h, 0);
                transform.rotate(Math.PI / 2);
                newWidth = h;
                newHeight = w;
                break;
            case 8:
                transform.translate(0, w);
                transform.rotate(-Math.PI / 2);
                newWidth = h;
                newHeight = w;
                break;
            default:
                return image;
        }

        BufferedImage rotated = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, transform, null);
        g.dispose();
        return rotated;
    }

    private BufferedImage toGrayscale(BufferedImage image) {
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return gray;
    }

    /**
     * Scale so the long side is within [1800, 3200] px. Receipts are long and narrow, so scaling on the
     * short side (old logic) blew a 550x1300 slip up to 1650x3900 while leaving 4000x3000 phone photos
     * at full 12 MP. Downscaling huge photos also cuts OCR time.
     */
    private BufferedImage fitForOcr(BufferedImage image) {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        double scale = 1;
        if (longSide > 3200) scale = 3200.0 / longSide;
        else if (longSide < 1800) scale = Math.min(3.0, 1800.0 / longSide);
        if (scale == 1) return image;

        int newWidth = (int) Math.round(image.getWidth() * scale);
        int newHeight = (int) Math.round(image.getHeight() * scale);
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < 1
            ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }
}
