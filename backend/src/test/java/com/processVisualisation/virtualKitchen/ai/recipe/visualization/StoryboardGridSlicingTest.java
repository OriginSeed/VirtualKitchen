package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Cutting a storyboard image into its rows x columns of panels ({@link VisualizationGridSlicer#sliceGrid}). */
public class StoryboardGridSlicingTest {

    @Test
    void aFourByFourImage_isCutAtItsGutters_intoSixteenPanelsInReadingOrder() throws Exception {
        // Gutters 6px wide and 5px off where an even grid puts them, as image models draw them.
        byte[] image = grid(400, 400, 4, 4, 6, 5);

        List<VisualizationGridSlicer.Tile> tiles = VisualizationGridSlicer.sliceGrid(image, 4, 4);

        assertEquals(16, tiles.size());
        for (int i = 0; i < 16; i++) {
            assertSolid(read(tiles.get(i).png()), colour(i / 4, i % 4), "panel " + (i + 1));
        }
        // First cell: up to the first gutter; the next starts right after it.
        assertEquals(0, tiles.get(0).x());
        assertEquals(105, tiles.get(0).width());
        assertEquals(111, tiles.get(1).x());
        assertEquals(111, tiles.get(4).y());
        // The crop says where each panel sits in the combined image.
        VisualizationGridSlicer.Tile last = tiles.get(15);
        assertEquals(400, last.x() + last.width());
        assertEquals(400, last.y() + last.height());
    }

    @Test
    void aThreeByThreeImage_andAWideGrid_areCutTheSameWay() throws Exception {
        List<VisualizationGridSlicer.Tile> nine = VisualizationGridSlicer.sliceGrid(grid(300, 300, 3, 3, 4, -4), 3, 3);
        assertEquals(9, nine.size());
        for (int i = 0; i < 9; i++) {
            assertSolid(read(nine.get(i).png()), colour(i / 3, i % 3), "panel " + (i + 1));
        }

        // 2 rows x 3 columns (five or six panels).
        List<VisualizationGridSlicer.Tile> six = VisualizationGridSlicer.sliceGrid(grid(300, 200, 2, 3, 6, 0), 2, 3);
        assertEquals(6, six.size());
        for (int i = 0; i < 6; i++) {
            assertSolid(read(six.get(i).png()), colour(i / 3, i % 3), "panel " + (i + 1));
        }
        assertEquals(100, six.get(0).width());
        assertEquals(100, six.get(0).height());
    }

    @Test
    void anImageWithoutGutters_isCutIntoEqualCells() throws Exception {
        BufferedImage image = new BufferedImage(90, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(90, 90, 90));
        g.fillRect(0, 0, 90, 60);
        g.dispose();

        List<VisualizationGridSlicer.Tile> tiles = VisualizationGridSlicer.sliceGrid(png(image), 2, 3);

        assertEquals(6, tiles.size());
        for (VisualizationGridSlicer.Tile tile : tiles) {
            assertEquals(30, tile.width());
            assertEquals(30, tile.height());
        }
        assertEquals(60, tiles.get(5).x());
        assertEquals(30, tiles.get(5).y());
    }

    @Test
    void aSinglePanel_isTheWholeImage() throws Exception {
        List<VisualizationGridSlicer.Tile> tiles = VisualizationGridSlicer.sliceGrid(grid(120, 80, 1, 1, 0, 0), 1, 1);

        assertEquals(1, tiles.size());
        assertEquals(120, tiles.get(0).width());
        assertEquals(80, tiles.get(0).height());
    }

    @Test
    void anUnreadableImage_orAnImpossibleGrid_isRefused() {
        assertThrows(IOException.class, () -> VisualizationGridSlicer.sliceGrid(new byte[]{1, 2, 3}, 2, 2));
        assertThrows(IOException.class, () -> VisualizationGridSlicer.sliceGrid(null, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> VisualizationGridSlicer.sliceGrid(new byte[0], 0, 2));
    }

    /**
     * A grid image: every cell one solid colour, white gutters {@code gutter} px wide, each shifted {@code shift} px
     * from where an even grid puts it.
     */
    public static byte[] grid(int width, int height, int rows, int columns, int gutter, int shift) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        for (int row = 0; row < rows; row++) {
            int top = row == 0 ? 0 : height * row / rows + shift + gutter;
            int bottom = row == rows - 1 ? height : height * (row + 1) / rows + shift;
            for (int column = 0; column < columns; column++) {
                int left = column == 0 ? 0 : width * column / columns + shift + gutter;
                int right = column == columns - 1 ? width : width * (column + 1) / columns + shift;
                g.setColor(colour(row, column));
                g.fillRect(left, top, right - left, bottom - top);
            }
        }
        g.dispose();
        return png(image);
    }

    private static Color colour(int row, int column) {
        return new Color(30 + row * 45, 30 + column * 45, 90);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** No gutter and no neighbour left in the panel: every pixel is the panel's own colour. */
    private static void assertSolid(BufferedImage panel, Color colour, String label) {
        for (int x = 0; x < panel.getWidth(); x++) {
            for (int y = 0; y < panel.getHeight(); y++) {
                assertEquals(colour.getRGB(), panel.getRGB(x, y), label + " pixel " + x + "," + y);
            }
        }
    }
}
