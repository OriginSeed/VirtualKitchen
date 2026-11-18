package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto;

import lombok.Data;

import java.util.Map;

/** Body of "fix this step's image". */
@Data
public class StoryboardStepFixRequestDTO {

    /** The process the step belongs to. */
    private Long processId;

    /** What should change in the image. */
    private String feedback;

    /** The models picked for this run (TEXT_TO_TEXT for the prompt, TEXT_TO_IMAGE for the image). */
    private Map<String, String> modelKeys;
}
