package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "item_cost")
public class ItemCost {

    public static final String SEQUENCE_NAME = "item_cost_sequence";

    @Id
    private Long id;

    private ItemType itemType;
    private Long itemId;

    private UnitType unit;

    private double costPerUnit;
    private String currency;

    private LocalDateTime effectiveFrom;

    @CreatedDate
    private LocalDateTime createdAt;
}
