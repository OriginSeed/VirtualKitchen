package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.ItemCost;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ItemCostRepository extends MongoRepository<ItemCost, Long> {

    List<ItemCost> findByItemTypeAndItemIdOrderByEffectiveFromDesc(String itemType, Long itemId);
}
