package com.processVisualisation.virtualKitchen.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MongoConfig {

    @Value("${spring.data.mongodb.uri:mongodb+srv://originSeed:originSeed123@originseed.fx177fa.mongodb.net/OriginSeed}")
    private String mongoUri;

    @Bean
    public MongoClient mongoClient() {
        System.out.println("========================================");
        System.out.println("Connecting to MongoDB with URI:");
        System.out.println(mongoUri);
        System.out.println("========================================");
        return MongoClients.create(mongoUri);
    }
}
