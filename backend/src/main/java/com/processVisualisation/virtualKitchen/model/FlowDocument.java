package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Document(collection = "flows")
public class FlowDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String flowId;

    @Indexed
    private String userId;

    @Indexed
    private Long templateId;

    private List<NodeDocument> nodes = new ArrayList<>();

    private List<EdgeDocument> edges = new ArrayList<>();

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Data
    public static class NodeDocument {
        private String id;
        private String type;
        private Map<String, Object> data = new LinkedHashMap<>();
        private PositionDocument position;
        private MeasuredDocument measured;
        private String parentId;
        private String extent;
        private Boolean draggable;
        private Boolean selectable;
        private Boolean deletable;
    }

    @Data
    public static class EdgeDocument {
        private String id;
        private String source;
        private String target;
        private String sourceHandle;
        private String targetHandle;
        private String type;
        private Boolean animated;
        private Map<String, Object> style;
        private Map<String, Object> data = new LinkedHashMap<>();
        private String label;
    }

    @Data
    public static class PositionDocument {
        private Double x;
        private Double y;
    }

    @Data
    public static class MeasuredDocument {
        private Double width;
        private Double height;
    }
}
