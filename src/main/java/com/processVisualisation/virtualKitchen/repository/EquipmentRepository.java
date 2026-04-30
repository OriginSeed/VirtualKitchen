package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.Equipment;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EquipmentRepository extends MongoRepository<Equipment, Long> {
    Optional<Equipment> findByName(String name);
    boolean existsByName(String name);
}
