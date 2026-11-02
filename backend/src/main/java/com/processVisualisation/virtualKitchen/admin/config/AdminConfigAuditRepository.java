package com.processVisualisation.virtualKitchen.admin.config;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminConfigAuditRepository extends MongoRepository<AdminConfigAudit, String> {
}
