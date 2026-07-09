package com.processVisualisation.virtualKitchen.model;

import lombok.Data;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "visualization_clip")
public class VisualizationClip {

    public static final String SEQUENCE_NAME = "visualization_clip_sequence";

    @Id
    private Long id;

    @Indexed
    private Long processTemplateId;

    private String parentClipId;

    private String clipId;

    private String title;

    private String description;

    private int stepOrder;

    private String mediaUrl;

    private String lastFramesUrl;

    private String thumbnailUrl;

    private String status;

    private String cameraAngle;

    private String sourceNodeId;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
