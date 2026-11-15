package com.processVisualisation.virtualKitchen.kitchen.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.kitchen.dto.InventoryRequestDTO;
import com.processVisualisation.virtualKitchen.kitchen.dto.InventoryResponseDTO;
import com.processVisualisation.virtualKitchen.kitchen.mapper.InventoryMapper;
import com.processVisualisation.virtualKitchen.kitchen.model.Inventory;
import com.processVisualisation.virtualKitchen.kitchen.repository.InventoryRepository;
import com.processVisualisation.virtualKitchen.store.model.Ingredient;
import com.processVisualisation.virtualKitchen.store.model.ItemType;
import com.processVisualisation.virtualKitchen.store.repository.EquipmentRepository;
import com.processVisualisation.virtualKitchen.store.repository.IngredientRepository;
import com.processVisualisation.virtualKitchen.store.units.UnitConversionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Manages inventory items that may be scoped to a kitchen or to a user. When
 * adding or updating stock, kitchen-scoped matches are preferred over
 * user-scoped matches; if no existing record is found a new one is created
 * with an id generated via {@link SequenceGeneratorService}, otherwise the
 * existing record's quantity is atomically incremented (after converting the
 * added amount into the record's unit). Kitchen-scoped lookups are
 * enriched with the item's display name via the ingredient/equipment
 * repositories.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    @Autowired
    private InventoryRepository repo;

    @Autowired
    private InventoryMapper mapper;

    @Autowired
    private IngredientRepository ingredientRepository;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private SequenceGeneratorService seq;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private UnitConversionService unitConversionService;

    /**
     * Creates a new inventory record or increments the quantity of an
     * existing one. Kitchen-scoped inventory is looked up first when
     * {@code dto.getKitchenId()} is present; if no match is found (or no
     * kitchen id was supplied) the method falls back to a user-scoped lookup.
     * When no existing record matches, a new one is created with a generated
     * id. Otherwise the requested amount is converted into the existing
     * record's unit (500 GRAM added to a KG row adds 0.5) and applied with a
     * single atomic {@code $inc}, so concurrent additions and recipe-order
     * reservations on the same row are never lost. If an existing record has
     * no kitchen id set but the request supplies one, it is backfilled.
     *
     * @param dto the item being added/updated, including its user/kitchen
     *            scope, item type/id, quantity and unit
     * @return the resulting inventory record's current state
     * @throws com.processVisualisation.virtualKitchen.store.units.UnitConversionException
     *         when the quantity cannot be converted into the existing record's unit
     */
    public InventoryResponseDTO addOrUpdate(InventoryRequestDTO dto){
        Inventory inv = findExisting(dto);

        if (inv == null) {
            inv = mapper.toEntity(dto);
            inv.setId(seq.generateSequence(Inventory.SEQUENCE_NAME));
            inv.setLastUpdated(LocalDateTime.now());
            Inventory created = repo.save(inv);
            log.info("event=inventory_updated inventoryId={} kitchenId={} userId={} itemType={} itemId={} created=true",
                    inv.getId(), inv.getKitchenId(), inv.getUserId(), inv.getItemType(), inv.getItemId());
            return mapper.toDTO(created);
        }

        double delta = quantityInRowUnit(dto, inv);
        Update update = new Update().inc("quantity", delta).set("lastUpdated", LocalDateTime.now());
        // If inventory existed but didn't have kitchenId set, and request provides it, persist it
        if (inv.getKitchenId() == null && dto.getKitchenId() != null) {
            update.set("kitchenId", dto.getKitchenId());
        }
        Inventory updated = mongoTemplate.findAndModify(
                Query.query(where("_id").is(inv.getId())), update,
                FindAndModifyOptions.options().returnNew(true), Inventory.class);
        log.info("event=inventory_updated inventoryId={} kitchenId={} userId={} itemType={} itemId={} created=false unitConverted={}",
                inv.getId(), inv.getKitchenId() != null ? inv.getKitchenId() : dto.getKitchenId(), inv.getUserId(),
                inv.getItemType(), inv.getItemId(), dto.getUnit() != null && inv.getUnit() != null && dto.getUnit() != inv.getUnit());
        return mapper.toDTO(updated != null ? updated : inv);
    }

    /**
     * Checks that {@link #addOrUpdate} can apply {@code dto} — in particular that its quantity can be
     * converted into the unit of the inventory row it would add to — without changing anything.
     *
     * @throws com.processVisualisation.virtualKitchen.store.units.UnitConversionException when it cannot
     */
    public void validateAdd(InventoryRequestDTO dto) {
        Inventory inv = findExisting(dto);
        if (inv != null) {
            quantityInRowUnit(dto, inv);
        }
    }

    private Inventory findExisting(InventoryRequestDTO dto) {
        Inventory inv = null;

        // Prefer kitchen-scoped inventory when kitchenId is provided
        if (dto.getKitchenId() != null) {
            inv = repo.findByKitchenIdAndItemTypeAndItemId(
                    dto.getKitchenId(), dto.getItemType(), dto.getItemId()
            ).orElse(null);
        }

        // Fallback to user-scoped inventory for backward compatibility
        if (inv == null) {
            inv = repo.findByUserIdAndItemTypeAndItemId(
                    dto.getUserId(), dto.getItemType(), dto.getItemId()
            ).orElse(null);
        }
        return inv;
    }

    /** {@code dto}'s quantity expressed in {@code inv}'s unit. */
    private double quantityInRowUnit(InventoryRequestDTO dto, Inventory inv) {
        if (dto.getUnit() == null || inv.getUnit() == null || dto.getUnit() == inv.getUnit()) {
            return dto.getQuantity();
        }
        Ingredient ingredient = dto.getItemType() == ItemType.INGREDIENT
                ? ingredientRepository.findById(dto.getItemId()).orElse(null)
                : null;
        return unitConversionService.convert(dto.getQuantity(), dto.getUnit().name(), inv.getUnit(), ingredient).value();
    }

    /**
     * Fetches all inventory items owned directly by the given user.
     *
     * @param userId id of the owning user
     * @return the user's inventory items
     */
    public List<InventoryResponseDTO> getByUser(Long userId){
        return repo.findByUserId(userId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Fetches all inventory items allocated to the given kitchen, enriching
     * each result with the item's display name and catalog image URL looked
     * up from the ingredient or equipment repository based on its item type.
     *
     * @param kitchenId id of the kitchen
     * @return the kitchen's inventory items, with item names and image URLs populated
     */
    public List<InventoryResponseDTO> getByKitchen(Long kitchenId){
        return repo.findByKitchenId(kitchenId)
                .stream()
                .map(inv -> {
                    InventoryResponseDTO dto = mapper.toDTO(inv);

                    // Enrich with item name and catalog image similar to KitchenInventoryService
                    if (inv.getItemType() == ItemType.INGREDIENT) {
                        ingredientRepository.findById(inv.getItemId()).ifPresent(ing -> {
                            dto.setItemName(ing.getName());
                            dto.setImageUrl(ing.getImageUrl());
                        });
                    } else if (inv.getItemType() == ItemType.EQUIPMENT) {
                        equipmentRepository.findById(inv.getItemId()).ifPresent(eq -> {
                            dto.setItemName(eq.getName());
                            dto.setImageUrl(eq.getImageUrl());
                        });
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }
}
