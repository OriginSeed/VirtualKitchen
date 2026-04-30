package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Data // Generates getters, setters, toString, etc.
@Document(collection = "users") // Marks this as a MongoDB collection
public class User {
    public static final String SEQUENCE_NAME = "users_sequence";
    @Id // Marks this as the primary key (MongoDB uses String/ObjectId)
    private long id;

    @Field("full_name") // Optional: map Java field name to a different DB field name
    private String name;

    @Indexed(unique = true) // Creates a unique index in MongoDB for fast lookups
    private String email;

    private String password;

    @CreatedDate // Automatically sets the date when the document is created
    private LocalDateTime createDate;

    private boolean active = true;
}
