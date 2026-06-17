package com.processVisualisation.virtualKitchen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProcessEquipmentUsageResponseDTO {

    private Long id;
    private Long processExecutionId;
    private Long equipmentId;
    private long usageDurationSec;
    private double costAtTime;
}
