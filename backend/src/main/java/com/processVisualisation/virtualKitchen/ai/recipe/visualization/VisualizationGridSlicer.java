package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a shared panel image (several Preparation steps drawn as equal panels of one image) into one image per step.
 * Two panels are stacked top and bottom; four are a 2x2 grid read left to right, top to bottom.
 * <p>
 * The image model is asked for thin plain white gutters between the panels, but does not always place them exactly in
 * the middle: each cut looks for the brightest, most uniform line near the middle and cuts out the whole gutter band
 * around it. When no gutter is found the image is cut exactly in the middle.
 */
public final class VisualizationGridSlicer {

    /** How far from the middle (as a share of the side) a gutter is looked for. */
    static final double SEARCH_SPAN = 0.10;
    /** Widest gutter band cut out, as a share of the side. */
    static final double MAX_GUTTER = 0.08;
    /** A gutter line is at least this bright on average (0-255)... */
    static final double MIN_GUTTER_BRIGHTNESS = 215;
    /** ...and varies at most this much along its length. */
    static final double MAX_GUTTER_SPREAD = 25;

    private VisualizationGridSlicer() {
    }

    /** Whether a group of this many steps is drawn as one panel image (2 or 4); 1 is drawn on its own. */
    public static boolean isPanelCount(int panels) {
        return panels == 2 || panels == 4;
    }

    /**
     * Cuts {@code image} into {@code panels} PNG images, in panel order.
     *
     * @param panels 2 (stacked top and bottom) or 4 (2x2)
     * @throws IOException when the image cannot be read or written
     */
    public static List<byte[]> slice(byte[] image, int panels) throws IOException {
        if (!isPanelCount(panels)) {
            throw new IllegalArgumentException("A panel image has 2 or 4 panels, not " + panels);
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) {
            throw new IOException("Unreadable panel image");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        Band rows = findGutter(source, false);
        List<BufferedImage> parts = new ArrayList<>();
        if (panels == 2) {
            parts.add(crop(source, 0, 0, width, rows.start()));
            parts.add(crop(source, 0, rows.end(), width, height));
        } else {
            Band columns = findGutter(source, true);
            parts.add(crop(source, 0, 0, columns.start(), rows.start()));
            parts.add(crop(source, columns.end(), 0, width, rows.start()));
            parts.add(crop(source, 0, rows.end(), columns.start(), height));
            parts.add(crop(source, columns.end(), rows.end(), width, height));
        }
        List<byte[]> encoded = new ArrayList<>();
        for (BufferedImage part : parts) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(part, "png", out)) {
                throw new IOException("Could not encode a panel as PNG");
            }
            encoded.add(out.toByteArray());
        }
        return encoded;
    }

    /** The gutter between two panels: lines {@code start} (inclusive) to {@code end} (exclusive) are cut out. */
    record Band(int start, int end) {
    }

    /**
     * The gutter across the image near its middle: a vertical one (a column band) when {@code vertical}, else a
     * horizontal one (a row band). An empty band at the exact middle when none is found.
     */
    static Band findGutter(BufferedImage image, boolean vertical) {
        int size = vertical ? image.getWidth() : image.getHeight();
        int middle = size / 2;
        int from = Math.max(1, (int) Math.floor(size * (0.5 - SEARCH_SPAN)));
        int to = Math.min(size - 2, (int) Math.ceil(size * (0.5 + SEARCH_SPAN)));
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int line = from; line <= to; line++) {
            double[] stats = lineStats(image, vertical, line);
            if (!isGutter(stats)) continue;
            // Brighter and flatter wins; ties go to the line nearest the middle.
            double score = stats[0] - 2 * stats[1] - Math.abs(line - middle) * 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        if (best < 0) {
            return new Band(middle, middle);
        }
        int maxBand = Math.max(1, (int) (size * MAX_GUTTER));
        int start = best;
        int end = best + 1;
        while (start - 1 >= 1 && end - (start - 1) <= maxBand && isGutter(lineStats(image, vertical, start - 1))) {
            start--;
        }
        while (end < size - 1 && (end + 1) - start <= maxBand && isGutter(lineStats(image, vertical, end))) {
            end++;
        }
        return new Band(start, end);
    }

    private static boolean isGutter(double[] stats) {
        return stats[0] >= MIN_GUTTER_BRIGHTNESS && stats[1] <= MAX_GUTTER_SPREAD;
    }

    /** Mean and standard deviation of the brightness along one column ({@code vertical}) or row. */
    private static double[] lineStats(BufferedImage image, boolean vertical, int line) {
        int length = vertical ? image.getHeight() : image.getWidth();
        double sum = 0;
        double sumSquares = 0;
        int count = 0;
        for (int i = 0; i < length; i += 2) {
            int rgb = vertical ? image.getRGB(line, i) : image.getRGB(i, line);
            double brightness = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
            sum += brightness;
            sumSquares += brightness * brightness;
            count++;
        }
        double mean = sum / count;
        double variance = Math.max(0, sumSquares / count - mean * mean);
        return new double[]{mean, Math.sqrt(variance)};
    }

    private static BufferedImage crop(BufferedImage source, int x0, int y0, int x1, int y1) {
        int width = Math.max(1, x1 - x0);
        int height = Math.max(1, y1 - y0);
        BufferedImage part = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        part.getGraphics().drawImage(source.getSubimage(x0, y0, width, height), 0, 0, null);
        return part;
    }
}
