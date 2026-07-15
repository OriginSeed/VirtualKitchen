package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.KitchenInventoryMapper;
import com.processVisualisation.virtualKitchen.model.*;
import com.processVisualisation.virtualKitchen.repository.*;
import com.processVisualisation.virtualKitchen.service.IKitchenInventoryService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class KitchenInventoryServiceImpl implements IKitchenInventoryService {

    @Autowired
    private KitchenInventoryRepository repo;

    @Autowired
    private KitchenInventoryMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private IngredientRepository ingredientRepository;

    @Override
    public KitchenInventoryResponseDTO create(KitchenInventoryRequestDTO dto){
        KitchenInventory ki = mapper.toEntity(dto);
        ki.setId(seq.generateSequence(KitchenInventory.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(ki));
    }

    @Override
    public List<KitchenInventoryResponseDTO> getByKitchen(Long kitchenId){
        return repo.findByKitchenId(kitchenId)
                .stream()
                .map(ki -> {
                    KitchenInventoryResponseDTO dto = KitchenInventoryResponseDTO.builder().build();
                    dto.setId(ki.getId());
                    dto.setKitchenId(ki.getKitchenId());
                    dto.setInventoryId(ki.getInventoryId());

                    // Fetch related inventory data
                    Optional<Inventory> inventory = inventoryRepository.findById(ki.getInventoryId());
                    if (inventory.isPresent()) {
                        Inventory inv = inventory.get();
                        dto.setQuantity(inv.getQuantity());
                        dto.setUnit(inv.getUnit() != null ? inv.getUnit().name() : null);
                        dto.setLastUpdated(inv.getLastUpdated());
                        dto.setItemType(inv.getItemType() != null ? inv.getItemType().name() : null);

                        // Fetch item name based on item type
                        if (inv.getItemType() == ItemType.INGREDIENT) {
                            Optional<Ingredient> ingredient = ingredientRepository.findById(inv.getItemId());
                            ingredient.ifPresent(ing -> dto.setItemName(ing.getName()));
                        } else if (inv.getItemType() == ItemType.EQUIPMENT) {
                            Optional<Equipment> equipment = equipmentRepository.findById(inv.getItemId());
                            equipment.ifPresent(eq -> dto.setItemName(eq.getName()));
                        }
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void delete(Long id){
        repo.deleteById(id);
    }
}
