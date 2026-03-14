package com.wyn.expensetracker;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.io.IOException;

/**
 * Preprocesses receipt images for better OCR accuracy.
 * Pipeline: EXIF rotation → grayscale → CLAHE contrast → sharpen → upscale.
 */
public class ReceiptImagePreprocessor {

    public BufferedImage preprocess(File imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Cannot read image file: " + imageFile.getName());
        }

        // 1. Apply EXIF orientation correction
        image = applyExifRotation(image, imageFile);

        // 2. Convert to grayscale
        image = toGrayscale(image);

        // 3. CLAHE contrast enhancement (recovers faded/creased text)
        image = applyClahe(image, 8, 2.5);

        // 4. Sharpen (crisps up blurry edges from folds/angles)
        image = sharpen(image);

        // 5. Upscale if text is likely too small for Tesseract
        image = upscaleIfNeeded(image);

        return image;
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
     * Contrast Limited Adaptive Histogram Equalization.
     * Enhances contrast locally so faded text near folds/creases becomes readable
     * without blowing out already-good regions.
     *
     * @param gridSize  number of tiles per dimension (e.g. 8 = 8x8 grid)
     * @param clipLimit contrast amplification limit (higher = more contrast, 2-4 typical)
     */
    private BufferedImage applyClahe(BufferedImage gray, int gridSize, double clipLimit) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        int tileW = w / gridSize;
        int tileH = h / gridSize;

        if (tileW < 2 || tileH < 2) return gray;

        // Compute clipped & redistributed histograms for each tile
        int[][] mappings = new int[gridSize * gridSize][256];
        for (int ty = 0; ty < gridSize; ty++) {
            for (int tx = 0; tx < gridSize; tx++) {
                int x0 = tx * tileW;
                int y0 = ty * tileH;
                int x1 = (tx == gridSize - 1) ? w : x0 + tileW;
                int y1 = (ty == gridSize - 1) ? h : y0 + tileH;

                // Build histogram for this tile
                int[] hist = new int[256];
                int tilePixels = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        hist[gray.getRaster().getSample(x, y, 0)]++;
                        tilePixels++;
                    }
                }

                // Clip histogram and redistribute
                int clipCount = (int) (clipLimit * tilePixels / 256);
                int excess = 0;
                for (int i = 0; i < 256; i++) {
                    if (hist[i] > clipCount) {
                        excess += hist[i] - clipCount;
                        hist[i] = clipCount;
                    }
                }
                int perBin = excess / 256;
                for (int i = 0; i < 256; i++) {
                    hist[i] += perBin;
                }

                // Build CDF mapping
                int[] cdf = new int[256];
                cdf[0] = hist[0];
                for (int i = 1; i < 256; i++) {
                    cdf[i] = cdf[i - 1] + hist[i];
                }
                int cdfMin = 0;
                for (int i = 0; i < 256; i++) {
                    if (cdf[i] > 0) { cdfMin = cdf[i]; break; }
                }
                int totalPixels = cdf[255];
                int idx = ty * gridSize + tx;
                for (int i = 0; i < 256; i++) {
                    mappings[idx][i] = totalPixels > cdfMin
                        ? (int) ((double) (cdf[i] - cdfMin) / (totalPixels - cdfMin) * 255)
                        : 0;
                }
            }
        }

        // Apply with bilinear interpolation between tile mappings
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = gray.getRaster().getSample(x, y, 0);

                // Find which tile center this pixel is relative to
                double gx = ((double) x / tileW) - 0.5;
                double gy = ((double) y / tileH) - 0.5;
                int tx0 = Math.max(0, Math.min(gridSize - 1, (int) Math.floor(gx)));
                int ty0 = Math.max(0, Math.min(gridSize - 1, (int) Math.floor(gy)));
                int tx1 = Math.min(gridSize - 1, tx0 + 1);
                int ty1 = Math.min(gridSize - 1, ty0 + 1);

                double fx = gx - tx0;
                double fy = gy - ty0;
                fx = Math.max(0, Math.min(1, fx));
                fy = Math.max(0, Math.min(1, fy));

                // Bilinear interpolation of the four surrounding tile mappings
                double v00 = mappings[ty0 * gridSize + tx0][pixel];
                double v10 = mappings[ty0 * gridSize + tx1][pixel];
                double v01 = mappings[ty1 * gridSize + tx0][pixel];
                double v11 = mappings[ty1 * gridSize + tx1][pixel];

                double top = v00 + fx * (v10 - v00);
                double bot = v01 + fx * (v11 - v01);
                int val = (int) (top + fy * (bot - top));
                result.getRaster().setSample(x, y, 0, Math.max(0, Math.min(255, val)));
            }
        }
        return result;
    }

    /**
     * Unsharp mask sharpening to crisp up text edges.
     */
    private BufferedImage sharpen(BufferedImage image) {
        float[] kernel = {
             0, -1,  0,
            -1,  5, -1,
             0, -1,  0
        };
        ConvolveOp op = new ConvolveOp(new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null);
        return op.filter(image, null);
    }

    private BufferedImage upscaleIfNeeded(BufferedImage image) {
        int minDim = Math.min(image.getWidth(), image.getHeight());
        if (minDim >= 3000) {
            return image;
        }

        double scale = 3000.0 / minDim;
        scale = Math.min(scale, 3.0);

        int newWidth = (int) (image.getWidth() * scale);
        int newHeight = (int) (image.getHeight() * scale);

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, image.getType());
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }
}
