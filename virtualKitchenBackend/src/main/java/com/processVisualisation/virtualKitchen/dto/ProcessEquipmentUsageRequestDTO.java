package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

@Data
public class ProcessEquipmentUsageRequestDTO {

    private Long processExecutionId;
    private Long equipmentId;
    private long usageDurationSec;
    private double costAtTime;
}
