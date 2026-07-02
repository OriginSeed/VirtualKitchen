package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.VisualizationClipResponseDTO;
import com.processVisualisation.virtualKitchen.dto.VisualizationResponseDTO;
import com.processVisualisation.virtualKitchen.model.VisualizationClip;
import com.processVisualisation.virtualKitchen.repository.FlowRepository;
import com.processVisualisation.virtualKitchen.repository.VisualizationClipRepository;
import com.processVisualisation.virtualKitchen.service.VisualizationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class VisualizationServiceImpl implements VisualizationService {

    private final VisualizationClipRepository clipRepository;
    private final FlowRepository flowRepository;

    public VisualizationServiceImpl(VisualizationClipRepository clipRepository, FlowRepository flowRepository) {
        this.clipRepository = clipRepository;
        this.flowRepository = flowRepository;
    }

    @Override
    public VisualizationResponseDTO generateVisualization(Long processTemplateId, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        List<VisualizationClip> existing = clipRepository.findByProcessTemplateIdOrderByStepOrderAsc(processTemplateId);
        List<VisualizationClipResponseDTO> clips = new ArrayList<>();
        VisualizationClip previous = null;

        int stepOrder = 0;
        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.getOrDefault("type", ""));
            if (!"recipeStepNode".equals(type)) {
                continue;
            }

            stepOrder++;
            String title = String.valueOf(((Map<String, Object>) node.getOrDefault("data", Map.of())).getOrDefault("title", "Step " + stepOrder));
            String description = String.valueOf(((Map<String, Object>) node.getOrDefault("data", Map.of())).getOrDefault("description", ""));
            String nodeId = String.valueOf(node.getOrDefault("id", ""));

            VisualizationClip clip = existing.stream()
                    .filter(candidate -> Objects.equals(candidate.getSourceNodeId(), nodeId))
                    .findFirst()
                    .orElseGet(VisualizationClip::new);

            clip.setProcessTemplateId(processTemplateId);
            clip.setParentClipId(previous == null ? null : previous.getClipId());
            clip.setClipId("clip-" + processTemplateId + "-" + stepOrder);
            clip.setTitle(title);
            clip.setDescription(description);
            clip.setStepOrder(stepOrder);
            // Video-generation extension will be wired here later once a model is available.
            // For now we only persist the clip plan metadata.
            clip.setMediaUrl(clip.getMediaUrl() == null ? "/clips/" + clip.getClipId() + ".mp4" : clip.getMediaUrl());
            clip.setThumbnailUrl(clip.getThumbnailUrl() == null ? "/thumbnails/" + clip.getClipId() + ".jpg" : clip.getThumbnailUrl());
            clip.setStatus(clip.getStatus() == null ? "pending" : clip.getStatus());
            clip.setCameraAngle(clip.getCameraAngle() == null ? "front" : clip.getCameraAngle());
            clip.setSourceNodeId(nodeId);
            clipRepository.save(clip);

            previous = clip;
            clips.add(toResponseDTO(clip));
        }

        VisualizationClipResponseDTO finalClip = clips.isEmpty() ? null : clips.get(clips.size() - 1);
        return VisualizationResponseDTO.builder()
                .processTemplateId(processTemplateId)
                .message("Visualization generated")
                .clips(clips)
                .finalClip(finalClip)
                .build();
    }

    private VisualizationClipResponseDTO toResponseDTO(VisualizationClip clip) {
        return VisualizationClipResponseDTO.builder()
                .id(clip.getId())
                .clipId(clip.getClipId())
                .parentClipId(clip.getParentClipId())
                .title(clip.getTitle())
                .description(clip.getDescription())
                .stepOrder(clip.getStepOrder())
                .mediaUrl(clip.getMediaUrl())
                .thumbnailUrl(clip.getThumbnailUrl())
                .status(clip.getStatus())
                .cameraAngle(clip.getCameraAngle())
                .sourceNodeId(clip.getSourceNodeId())
                .build();
    }
}
