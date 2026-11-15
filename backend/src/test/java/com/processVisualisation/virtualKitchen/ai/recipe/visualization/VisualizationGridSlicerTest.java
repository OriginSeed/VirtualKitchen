package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualizationGridSlicerTest {

    private static final Color[] PANEL_COLOURS = {
            new Color(200, 30, 30), new Color(30, 160, 30), new Color(30, 30, 200), new Color(160, 120, 20)};

    @Test
    void aTwoByTwoImage_isCutAtItsGutters_intoFourPanelsInReadingOrder() throws Exception {
        // Gutters off-centre (column 110-117, row 90-95 of 200x200), as image models draw them.
        byte[] image = grid(200, 200, 110, 8, 90, 6);

        List<byte[]> panels = VisualizationGridSlicer.slice(image, 4);

        assertEquals(4, panels.size());
        for (int i = 0; i < 4; i++) {
            BufferedImage panel = read(panels.get(i));
            assertSolid(panel, PANEL_COLOURS[i]);
        }
        assertEquals(110, read(panels.get(0)).getWidth());
        assertEquals(90, read(panels.get(0)).getHeight());
        assertEquals(200 - 118, read(panels.get(1)).getWidth());
        assertEquals(200 - 96, read(panels.get(2)).getHeight());
    }

    @Test
    void aStackedImage_isCutAtItsHorizontalGutter_intoTwoWidePanels() throws Exception {
        byte[] image = stacked(200, 200, 104, 6);

        List<byte[]> panels = VisualizationGridSlicer.slice(image, 2);

        assertEquals(2, panels.size());
        assertSolid(read(panels.get(0)), PANEL_COLOURS[0]);
        assertSolid(read(panels.get(1)), PANEL_COLOURS[1]);
        assertEquals(200, read(panels.get(0)).getWidth());
        assertEquals(104, read(panels.get(0)).getHeight());
        assertEquals(200 - 110, read(panels.get(1)).getHeight());
    }

    @Test
    void anImageWithoutGutters_isCutExactlyInTheMiddle() throws Exception {
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(90, 90, 90));
        g.fillRect(0, 0, 100, 80);
        g.dispose();

        List<byte[]> panels = VisualizationGridSlicer.slice(png(image), 4);

        assertEquals(50, read(panels.get(0)).getWidth());
        assertEquals(40, read(panels.get(0)).getHeight());
        assertEquals(50, read(panels.get(3)).getWidth());
        assertEquals(40, read(panels.get(3)).getHeight());
    }

    @Test
    void onlyTwoOrFourPanels() {
        assertThrows(IllegalArgumentException.class, () -> VisualizationGridSlicer.slice(new byte[0], 3));
        assertThrows(java.io.IOException.class, () -> VisualizationGridSlicer.slice(new byte[]{1, 2, 3}, 4));
    }

    @Test
    void theGridPrompt_namesTheLayout_andEachPanelInCookingOrder() {
        RecipeProcessVisualizationPromptBuilder builder = new RecipeProcessVisualizationPromptBuilder();
        VisualizationImageProperties.Scene style = new VisualizationImageProperties.Scene();
        SceneContext scene = style.equipment();

        String four = builder.buildGridImageRequestText(List.of(
                new RecipeProcessVisualizationPromptBuilder.GridPanel("Raw chicken. A knife cutting.", VisualFocus.CUTTING_BOARD),
                new RecipeProcessVisualizationPromptBuilder.GridPanel("Chicken in yogurt.", VisualFocus.PREP_BOWL),
                new RecipeProcessVisualizationPromptBuilder.GridPanel("Spices added.", VisualFocus.PREP_BOWL),
                new RecipeProcessVisualizationPromptBuilder.GridPanel("Coated chicken.", VisualFocus.PREP_BOWL)),
                scene, style, "Part of making Chicken Curry", false);
        assertTrue(four.startsWith("One image made of 4 equal square panels in a 2x2 grid"), four);
        assertTrue(four.contains("Top-left panel: A wooden cutting board on a light grey stone countertop fills most of the frame. Raw chicken."), four);
        assertTrue(four.contains("Bottom-right panel: A plain steel mixing bowl"), four);
        assertTrue(four.contains("Context: Part of making Chicken Curry"), four);
        assertTrue(!four.contains(RecipeProcessVisualizationPromptBuilder.GRID_PREVIOUS_INSTRUCTION), four);

        String two = builder.buildGridImageRequestText(List.of(
                new RecipeProcessVisualizationPromptBuilder.GridPanel("Onion halves.", VisualFocus.CUTTING_BOARD),
                new RecipeProcessVisualizationPromptBuilder.GridPanel("Chopped onion.", VisualFocus.CUTTING_BOARD)),
                scene, style, null, true);
        assertTrue(two.startsWith("One image made of 2 equal wide panels stacked one above the other"), two);
        assertTrue(two.indexOf("Top panel:") < two.indexOf("Bottom panel:"), two);
        assertTrue(two.endsWith(RecipeProcessVisualizationPromptBuilder.GRID_PREVIOUS_INSTRUCTION), two);
    }

    private static byte[] grid(int width, int height, int gutterX, int gutterWidth, int gutterY, int gutterHeight) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        int right = gutterX + gutterWidth;
        int bottom = gutterY + gutterHeight;
        fill(g, PANEL_COLOURS[0], 0, 0, gutterX, gutterY);
        fill(g, PANEL_COLOURS[1], right, 0, width - right, gutterY);
        fill(g, PANEL_COLOURS[2], 0, bottom, gutterX, height - bottom);
        fill(g, PANEL_COLOURS[3], right, bottom, width - right, height - bottom);
        g.dispose();
        return png(image);
    }

    private static byte[] stacked(int width, int height, int gutterY, int gutterHeight) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        fill(g, PANEL_COLOURS[0], 0, 0, width, gutterY);
        fill(g, PANEL_COLOURS[1], 0, gutterY + gutterHeight, width, height - gutterY - gutterHeight);
        g.dispose();
        return png(image);
    }

    private static void fill(Graphics2D g, Color colour, int x, int y, int width, int height) {
        g.setColor(colour);
        g.fillRect(x, y, width, height);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** No white gutter left in the panel: every pixel is the panel's own colour. */
    private static void assertSolid(BufferedImage panel, Color colour) {
        for (int x = 0; x < panel.getWidth(); x++) {
            for (int y = 0; y < panel.getHeight(); y++) {
                assertEquals(colour.getRGB(), panel.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }
}
