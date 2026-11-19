package com.processVisualisation.virtualKitchen.kitchen.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.kitchen.dto.InventoryRequestDTO;
import com.processVisualisation.virtualKitchen.kitchen.mapper.InventoryMapper;
import com.processVisualisation.virtualKitchen.kitchen.model.Inventory;
import com.processVisualisation.virtualKitchen.kitchen.repository.InventoryRepository;
import com.processVisualisation.virtualKitchen.store.model.ItemType;
import com.processVisualisation.virtualKitchen.store.repository.EquipmentRepository;
import com.processVisualisation.virtualKitchen.store.repository.IngredientRepository;
import com.processVisualisation.virtualKitchen.store.units.UnitConversionException;
import com.processVisualisation.virtualKitchen.store.units.UnitConversionService;
import com.processVisualisation.virtualKitchen.store.units.UnitType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Adding stock (shop checkout) converts units into the existing row and applies an atomic $inc. */
class InventoryServiceTest {

    private final InventoryRepository repository = mock(InventoryRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final IngredientRepository ingredients = mock(IngredientRepository.class);
    private final InventoryService service = new InventoryService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "repo", repository);
        ReflectionTestUtils.setField(service, "mapper", new InventoryMapper());
        ReflectionTestUtils.setField(service, "ingredientRepository", ingredients);
        ReflectionTestUtils.setField(service, "equipmentRepository", mock(EquipmentRepository.class));
        ReflectionTestUtils.setField(service, "seq", mock(SequenceGeneratorService.class));
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "unitConversionService", UnitConversionService.withDefaults());
        when(ingredients.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void convertsTheAddedAmountIntoTheRowsUnitAndIncrementsAtomically() {
        Inventory flour = row(UnitType.KG, 2);
        when(repository.findFirstByKitchenIdAndItemTypeAndItemIdOrderByIdAsc(70L, ItemType.INGREDIENT, 101L)).thenReturn(Optional.of(flour));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Inventory.class)))
                .thenReturn(row(UnitType.KG, 2.5));

        service.addOrUpdate(request(500, UnitType.GRAM));

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(Inventory.class));
        Document inc = (Document) update.getValue().getUpdateObject().get("$inc");
        assertEquals(0.5, ((Number) inc.get("quantity")).doubleValue(), 1e-9, "500 g adds 0.5 kg, not 500 kg");
        verify(repository, never()).save(any());
    }

    @Test
    void validationRejectsUnitsThatCannotBeAddedWithoutChangingAnything() {
        Inventory flour = row(UnitType.KG, 2);
        when(repository.findFirstByKitchenIdAndItemTypeAndItemIdOrderByIdAsc(70L, ItemType.INGREDIENT, 101L)).thenReturn(Optional.of(flour));
        InventoryRequestDTO odd = request(1, UnitType.GRAM);
        odd.setUnit(null);
        service.validateAdd(odd); // same-unit/unknown-unit requests pass through unchanged

        ReflectionTestUtils.setField(service, "unitConversionService", new UnitConversionService(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())); // a config with no units at all
        assertThrows(UnitConversionException.class, () -> service.validateAdd(request(1, UnitType.GRAM)));
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Inventory.class));
    }

    @Test
    void concurrentFirstAddOfTheSameItemMergesIntoTheRowThatWon() {
        Inventory winner = row(UnitType.KG, 1);
        // No row yet when this request looked; by the time it inserts, a concurrent add has created one.
        when(repository.findFirstByKitchenIdAndItemTypeAndItemIdOrderByIdAsc(70L, ItemType.INGREDIENT, 101L))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.insert(any(Inventory.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Inventory.class)))
                .thenReturn(row(UnitType.KG, 1.5));

        service.addOrUpdate(request(500, UnitType.GRAM));

        verify(mongoTemplate).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Inventory.class));
    }

    @Test
    void kitchenPantryLooksUpItsCatalogItemsInOneQueryPerType() {
        Inventory flour = row(UnitType.KG, 1);
        Inventory sugar = row(UnitType.KG, 1);
        sugar.setId(10L);
        sugar.setItemId(102L);
        when(repository.findByKitchenId(70L)).thenReturn(java.util.List.of(flour, sugar));
        com.processVisualisation.virtualKitchen.store.model.Ingredient flourItem = new com.processVisualisation.virtualKitchen.store.model.Ingredient();
        flourItem.setId(101L);
        flourItem.setName("Flour");
        when(ingredients.findAllById(any())).thenReturn(java.util.List.of(flourItem));

        java.util.List<com.processVisualisation.virtualKitchen.kitchen.dto.InventoryResponseDTO> pantry = service.getByKitchen(70L);

        verify(ingredients).findAllById(java.util.Set.of(101L, 102L));
        verify(ingredients, never()).findById(any());
        assertEquals("Flour", pantry.get(0).getItemName());
        assertEquals(null, pantry.get(1).getItemName());
    }

    private static Inventory row(UnitType unit, double quantity) {
        Inventory row = new Inventory();
        row.setId(9L);
        row.setKitchenId(70L);
        row.setUserId(7L);
        row.setItemType(ItemType.INGREDIENT);
        row.setItemId(101L);
        row.setQuantity(quantity);
        row.setUnit(unit);
        return row;
    }

    private static InventoryRequestDTO request(double quantity, UnitType unit) {
        InventoryRequestDTO dto = new InventoryRequestDTO();
        dto.setUserId(7L);
        dto.setKitchenId(70L);
        dto.setItemType(ItemType.INGREDIENT);
        dto.setItemId(101L);
        dto.setQuantity(quantity);
        dto.setUnit(unit);
        return dto;
    }
}
