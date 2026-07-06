package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.FlowSaveRequestDTO;
import com.processVisualisation.virtualKitchen.dto.FlowSaveResponseDTO;
import com.processVisualisation.virtualKitchen.model.FlowDocument;
import com.processVisualisation.virtualKitchen.repository.FlowRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FlowService {

    private final FlowRepository flowRepository;

    public FlowService(FlowRepository flowRepository) {
        this.flowRepository = flowRepository;
    }

    public FlowSaveResponseDTO saveFlow(FlowSaveRequestDTO request) {
        FlowDocument document = Optional.ofNullable(request.getFlowId())
                .flatMap(flowRepository::findByFlowId)
                .orElseGet(FlowDocument::new);

        document.setFlowId(request.getFlowId());
        document.setUserId(request.getUserId());
        document.setTemplateId(request.getTemplateId());
        document.setNodes(mapNodes(request.getNodes()));
        document.setEdges(mapEdges(request.getEdges()));

        flowRepository.save(document);

        FlowSaveResponseDTO response = new FlowSaveResponseDTO();
        response.setFlowId(request.getFlowId());
        response.setUserId(request.getUserId());
        response.setMessage("Flow saved successfully");
        return response;
    }

    public Optional<FlowDocument> getFlow(String flowId) {
        return flowRepository.findByFlowId(flowId);
    }

    private List<FlowDocument.NodeDocument> mapNodes(List<Map<String, Object>> nodes) {
        List<FlowDocument.NodeDocument> result = new ArrayList<>();
        if (nodes == null) {
            return result;
        }

        for (Map<String, Object> node : nodes) {
            FlowDocument.NodeDocument document = new FlowDocument.NodeDocument();
            document.setId((String) node.get("id"));
            document.setType((String) node.get("type"));
            document.setData(extractObject(node, "data"));

            Map<String, Object> position = extractObject(node, "position");
            if (position != null) {
                FlowDocument.PositionDocument positionDocument = new FlowDocument.PositionDocument();
                positionDocument.setX(toDouble(position.get("x")));
                positionDocument.setY(toDouble(position.get("y")));
                document.setPosition(positionDocument);
            }

            Map<String, Object> measured = extractObject(node, "measured");
            if (measured != null) {
                FlowDocument.MeasuredDocument measuredDocument = new FlowDocument.MeasuredDocument();
                measuredDocument.setWidth(toDouble(measured.get("width")));
                measuredDocument.setHeight(toDouble(measured.get("height")));
                document.setMeasured(measuredDocument);
            }

            document.setParentId((String) node.get("parentId"));
            document.setExtent((String) node.get("extent"));
            document.setDraggable((Boolean) node.get("draggable"));
            document.setSelectable((Boolean) node.get("selectable"));
            document.setDeletable((Boolean) node.get("deletable"));
            result.add(document);
        }
        return result;
    }

    private List<FlowDocument.EdgeDocument> mapEdges(List<Map<String, Object>> edges) {
        List<FlowDocument.EdgeDocument> result = new ArrayList<>();
        if (edges == null) {
            return result;
        }

        for (Map<String, Object> edge : edges) {
            FlowDocument.EdgeDocument document = new FlowDocument.EdgeDocument();
            document.setId((String) edge.get("id"));
            document.setSource((String) edge.get("source"));
            document.setTarget((String) edge.get("target"));
            document.setSourceHandle((String) edge.get("sourceHandle"));
            document.setTargetHandle((String) edge.get("targetHandle"));
            document.setType((String) edge.get("type"));
            document.setAnimated((Boolean) edge.get("animated"));
            document.setStyle(extractObject(edge, "style"));
            document.setData(extractObject(edge, "data"));
            document.setLabel((String) edge.get("label"));
            result.add(document);
        }
        return result;
    }

    private Map<String, Object> extractObject(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return null;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
