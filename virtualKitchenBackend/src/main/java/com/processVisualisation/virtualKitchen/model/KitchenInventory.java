package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "kitchen_inventory")
public class KitchenInventory {

    public static final String SEQUENCE_NAME = "kitchen_inventory_sequence";

    @Id
    private Long id;

    @Indexed
    private Long kitchenId;

    private Long inventoryId;
}
