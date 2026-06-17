package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "step_execution")
public class StepExecution {

    public static final String SEQUENCE_NAME = "step_execution_sequence";

    @Id
    private Long id;

    @Indexed
    private Long processExecutionId;

    private Long stepDefinitionId;

    private StepStatus status;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String notes;
}
