package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.VisualizationResponseDTO;
import com.processVisualisation.virtualKitchen.model.FlowDocument;
import com.processVisualisation.virtualKitchen.repository.FlowRepository;
import com.processVisualisation.virtualKitchen.repository.VisualizationClipRepository;
import com.processVisualisation.virtualKitchen.service.impl.VisualizationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisualizationServiceImplTest {

    @Test
    void shouldGenerateRootAndChildClipsForOrderedSteps() {
        VisualizationClipRepository clipRepository = mock(VisualizationClipRepository.class);
        FlowRepository flowRepository = mock(FlowRepository.class);
        when(clipRepository.findByProcessTemplateIdOrderByStepOrderAsc(101L)).thenReturn(List.of());
        when(clipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(flowRepository.findByFlowId("101")).thenReturn(java.util.Optional.empty());

        VisualizationServiceImpl service = new VisualizationServiceImpl(clipRepository, flowRepository);

        VisualizationResponseDTO response = service.generateVisualization(
                101L,
                List.of(
                        Map.of("id", "node-1", "type", "recipeStepNode", "data", Map.of("title", "Prep")),
                        Map.of("id", "node-2", "type", "recipeStepNode", "data", Map.of("title", "Cook"))
                ),
                List.of()
        );

        assertNotNull(response);
        assertEquals(2, response.getClips().size());
        assertNull(response.getClips().get(0).getParentClipId());
        assertEquals(response.getClips().get(0).getClipId(), response.getClips().get(1).getParentClipId());
        assertEquals("Prep", response.getClips().get(0).getTitle());
        assertEquals("Cook", response.getClips().get(1).getTitle());
    }
}
