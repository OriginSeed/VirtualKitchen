package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

/**
 * The grid of a storyboard image: equal panels read left to right, top to bottom. The grid is as square as the panel
 * count allows; when the count does not fill it, the last cells stay empty.
 */
public record StoryboardLayout(int rows, int columns) {

    public static StoryboardLayout of(int panels) {
        if (panels < 1) {
            throw new IllegalArgumentException("A storyboard has at least one panel");
        }
        int columns = (int) Math.ceil(Math.sqrt(panels));
        int rows = (int) Math.ceil(panels / (double) columns);
        return new StoryboardLayout(rows, columns);
    }

    public int cells() {
        return rows * columns;
    }

    /** "row 2, column 3" of the 1-based panel {@code sequence}. */
    public String position(int sequence) {
        int index = sequence - 1;
        return "row " + (index / columns + 1) + ", column " + (index % columns + 1);
    }
}
