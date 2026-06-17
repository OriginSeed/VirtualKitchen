package com.processVisualisation.virtualKitchen.model;


import lombok.Getter;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Document(collection = "database_sequences")
public class DBSequence {
    @Id
    private String name; // collection name (e.g., "users_sequence")
    private long seq;
}
