package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "process_execution")
public class ProcessExecution {

    public static final String SEQUENCE_NAME = "process_execution_sequence";

    @Id
    private Long id;

    @Indexed
    private Long processTemplateId;

    @Indexed
    private Long userId;

    private Long kitchenId;

    private ProcessStatus status;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String generatedMediaUrl;
}
