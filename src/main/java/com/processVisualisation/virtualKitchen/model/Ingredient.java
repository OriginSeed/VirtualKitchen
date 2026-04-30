package com.processVisualisation.virtualKitchen.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDate;

@Data
@Document (collation =  "ingredient")
public class Ingredient {

    @Id
    private String ingId;

    @Field("ingName")
    private String ingName;

    private String ingDescription;

    @CreatedDate
    private LocalDate ingCreateDate;
}
