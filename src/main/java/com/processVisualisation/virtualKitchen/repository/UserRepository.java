package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.User;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository // Marks this as a Data Access component
public interface UserRepository extends MongoRepository<User, Long> {
    // Custom query method: Spring Boot implements this automatically!
    Optional<User> findByEmail(String email);
}
