package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.VisualizationResponseDTO;
import com.processVisualisation.virtualKitchen.model.FlowDocument;
import com.processVisualisation.virtualKitchen.repository.FlowRepository;
import com.processVisualisation.virtualKitchen.repository.VisualizationClipRepository;
import com.processVisualisation.virtualKitchen.service.impl.VisualizationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        
        // Create flow document with nodes and templateId
        FlowDocument flow = new FlowDocument();
        flow.setFlowId("flow-101");
        flow.setTemplateId(101L);
        flow.setNodes(createTestNodes());
        flow.setEdges(new ArrayList<>());
        
        when(flowRepository.findByFlowId("flow-101")).thenReturn(java.util.Optional.of(flow));

        VisualizationServiceImpl service = new VisualizationServiceImpl(clipRepository, flowRepository);

        VisualizationResponseDTO response = service.generateVisualization("flow-101");

        assertNotNull(response);
        assertEquals(2, response.getClips().size());
        assertNull(response.getClips().get(0).getParentClipId());
        assertEquals(response.getClips().get(0).getClipId(), response.getClips().get(1).getParentClipId());
        assertEquals("Prep", response.getClips().get(0).getTitle());
        assertEquals("Cook", response.getClips().get(1).getTitle());
    }

    private List<FlowDocument.NodeDocument> createTestNodes() {
        List<FlowDocument.NodeDocument> nodes = new ArrayList<>();
        
        FlowDocument.NodeDocument node1 = new FlowDocument.NodeDocument();
        node1.setId("node-1");
        node1.setType("recipeStepNode");
        Map<String, Object> data1 = new LinkedHashMap<>();
        data1.put("title", "Prep");
        node1.setData(data1);
        nodes.add(node1);
        
        FlowDocument.NodeDocument node2 = new FlowDocument.NodeDocument();
        node2.setId("node-2");
        node2.setType("recipeStepNode");
        Map<String, Object> data2 = new LinkedHashMap<>();
        data2.put("title", "Cook");
        node2.setData(data2);
        nodes.add(node2);
        
        return nodes;
    }
}
