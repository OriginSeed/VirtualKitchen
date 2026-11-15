package com.processVisualisation.virtualKitchen.kitchen.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.kitchen.dto.KitchenInventoryRequestDTO;
import com.processVisualisation.virtualKitchen.kitchen.dto.KitchenInventoryResponseDTO;
import com.processVisualisation.virtualKitchen.kitchen.mapper.KitchenInventoryMapper;
import com.processVisualisation.virtualKitchen.kitchen.model.Inventory;
import com.processVisualisation.virtualKitchen.kitchen.model.KitchenInventory;
import com.processVisualisation.virtualKitchen.kitchen.repository.InventoryRepository;
import com.processVisualisation.virtualKitchen.kitchen.repository.KitchenInventoryRepository;
import com.processVisualisation.virtualKitchen.store.model.Equipment;
import com.processVisualisation.virtualKitchen.store.model.Ingredient;
import com.processVisualisation.virtualKitchen.store.model.ItemType;
import com.processVisualisation.virtualKitchen.store.repository.EquipmentRepository;
import com.processVisualisation.virtualKitchen.store.repository.IngredientRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persists kitchen-to-inventory-item association records via
 * {@link KitchenInventoryRepository}, assigning generated ids through
 * {@link SequenceGeneratorService}. When listing a kitchen's associations,
 * each record is enriched by joining against the underlying
 * {@link Inventory} record (quantity, unit, last-updated timestamp, item
 * type) and, depending on item type, the ingredient or equipment repository
 * for the item's display name.
 */
@Service
public class KitchenInventoryService {

    private static final Logger log = LoggerFactory.getLogger(KitchenInventoryService.class);

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

    /**
     * Creates a new kitchen-inventory association with a generated id and
     * persists it.
     *
     * @param dto the kitchen id and inventory id to associate
     * @return the created association
     */
    public KitchenInventoryResponseDTO create(KitchenInventoryRequestDTO dto){
        KitchenInventory ki = mapper.toEntity(dto);
        ki.setId(seq.generateSequence(KitchenInventory.SEQUENCE_NAME));
        KitchenInventory saved = repo.save(ki);
        log.info("event=kitchen_inventory_created kitchenInventoryId={} kitchenId={} inventoryId={}",
                ki.getId(), ki.getKitchenId(), ki.getInventoryId());
        return mapper.toDTO(saved);
    }

    /**
     * Fetches all kitchen-inventory associations for the given kitchen and
     * enriches each with details of the linked inventory record: quantity,
     * unit, last-updated timestamp, item type, and (looked up from the
     * ingredient or equipment repository based on item type) the item's
     * display name. If the linked inventory record cannot be found, the
     * enrichment fields are left unset on the returned DTO.
     *
     * @param kitchenId id of the kitchen
     * @return the kitchen's inventory associations, enriched with item details
     */
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

    /**
     * Deletes the kitchen-inventory association with the given id.
     *
     * @param id id of the association to delete
     */
    public void delete(Long id){
        repo.deleteById(id);
        log.info("event=kitchen_inventory_deleted kitchenInventoryId={}", id);
    }
}
