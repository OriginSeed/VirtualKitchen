package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.VisualizationRequestDTO;
import com.processVisualisation.virtualKitchen.dto.VisualizationResponseDTO;
import com.processVisualisation.virtualKitchen.service.VisualizationService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/visualizations")
public class VisualizationController {

    private final VisualizationService visualizationService;

    public VisualizationController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    @PostMapping("/{processTemplateId}")
    public ApiResponse<VisualizationResponseDTO> generate(@PathVariable Long processTemplateId,
                                                          @RequestBody VisualizationRequestDTO request) {
        request.setProcessTemplateId(processTemplateId);
        return build(visualizationService.generateVisualization(
                request.getProcessTemplateId(),
                request.getNodes(),
                request.getEdges()), "generated");
    }

    private <T> ApiResponse<T> build(T data, String msg) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
