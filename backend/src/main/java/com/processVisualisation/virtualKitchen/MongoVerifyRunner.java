package com.processVisualisation.virtualKitchen;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class MongoVerifyRunner implements CommandLineRunner {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public void run(String... args) {

        System.out.println("Database = "
                + mongoTemplate.getDb().getName());

        System.out.println("Collections = "
                + mongoTemplate.getCollectionNames());

        mongoTemplate.save(
                new org.bson.Document("name", "verify"),
                "testCollection"
        );

        System.out.println("Document inserted");
    }
}