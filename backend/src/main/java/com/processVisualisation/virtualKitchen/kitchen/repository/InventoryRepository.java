package com.processVisualisation.virtualKitchen.kitchen.repository;

import com.processVisualisation.virtualKitchen.kitchen.model.Inventory;
import com.processVisualisation.virtualKitchen.store.model.ItemType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for persisting and querying {@code Inventory}
 * entities, supporting lookups scoped either to an individual user or to a
 * kitchen.
 */
public interface InventoryRepository extends MongoRepository<Inventory, Long> {

    /**
     * Fetches all inventory items owned directly by the given user.
     */
    List<Inventory> findByUserId(Long userId);

    /**
     * Fetches all inventory items allocated to the given kitchen.
     */
    List<Inventory> findByKitchenId(Long kitchenId);

    /**
     * Fetches the inventory record for a specific item type/id combination owned
     * by the given user, if one exists. "First" (oldest): rows created before the unique kitchen index existed may
     * be duplicated, and a lookup must still answer instead of failing.
     */
    Optional<Inventory> findFirstByUserIdAndItemTypeAndItemIdOrderByIdAsc(
            Long userId, ItemType itemType, Long itemId
    );

    /**
     * Fetches the inventory record for a specific item type/id combination
     * allocated to the given kitchen, if one exists (the oldest, should duplicates exist).
     */
    Optional<Inventory> findFirstByKitchenIdAndItemTypeAndItemIdOrderByIdAsc(
            Long kitchenId, ItemType itemType, Long itemId
    );
}
