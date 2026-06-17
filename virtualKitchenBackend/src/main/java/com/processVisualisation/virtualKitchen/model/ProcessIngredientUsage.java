package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "process_ingredient_usage")
public class ProcessIngredientUsage {

    public static final String SEQUENCE_NAME = "process_ingredient_usage_sequence";

    @Id
    private Long id;

    @Indexed
    private Long processExecutionId;

    private Long ingredientId;

    private double quantityUsed;
    private UnitType unit;

    private double costAtTime;
}
