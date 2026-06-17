package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "process_equipment_usage")
public class ProcessEquipmentUsage {

    public static final String SEQUENCE_NAME = "process_equipment_usage_sequence";

    @Id
    private Long id;

    @Indexed
    private Long processExecutionId;

    private Long equipmentId;

    private long usageDurationSec;

    private double costAtTime;
}
