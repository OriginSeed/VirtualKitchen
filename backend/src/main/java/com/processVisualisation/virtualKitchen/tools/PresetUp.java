package com.processVisualisation.virtualKitchen.tools;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;

public class PresetUp {

    // Edit these if you prefer hard-coded values.
    private static final String DEFAULT_USER_EMAIL = "demo.user@example.com";
    private static final String DEFAULT_USER_NAME = "Demo User";
    private static final String DEFAULT_KITCHEN_NAME = "Demo Kitchen";

    public static void main(String[] args) throws Exception {
        String mongoUri = resolveMongoUri();
        System.out.println("Using Mongo URI: " + (mongoUri == null ? "(null)" : "[hidden]") );
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoDatabase db = client.getDatabase(getDatabaseNameFromUri(mongoUri));

            long userId = generateSequence(db, "users_sequence");
            Document user = upsertUser(db, userId, DEFAULT_USER_NAME, DEFAULT_USER_EMAIL);

            long kitchenId = generateSequence(db, "kitchen_sequence");
            Document kitchen = upsertKitchen(db, kitchenId, DEFAULT_KITCHEN_NAME, userId);

            long eq1 = generateSequence(db, "equipment_sequence");
            Document kettle = upsertEquipment(db, eq1, "Electric Kettle", "1.7 liter kettle for boiling water");

            long eq2 = generateSequence(db, "equipment_sequence");
            Document pan = upsertEquipment(db, eq2, "Nonstick Frying Pan", "12-inch pan for cooking and searing");

            long ing1 = generateSequence(db, "ingredients_sequence");
            Document flour = upsertIngredient(db, ing1, "All-purpose Flour", "Standard cooking flour", "KG");

            long ing2 = generateSequence(db, "ingredients_sequence");
            Document salt = upsertIngredient(db, ing2, "Sea Salt", "Fine sea salt", "GRAM");

            long inv1 = generateSequence(db, "inventory_sequence");
            upsertInventory(db, inv1, userId, "INGREDIENT", flour.getLong("_id"), 2.0, "KG");

            long inv2 = generateSequence(db, "inventory_sequence");
            upsertInventory(db, inv2, userId, "INGREDIENT", salt.getLong("_id"), 500.0, "GRAM");

            long inv3 = generateSequence(db, "inventory_sequence");
            upsertInventory(db, inv3, userId, "EQUIPMENT", kettle.getLong("_id"), 1.0, "COUNT");

            long inv4 = generateSequence(db, "inventory_sequence");
            upsertInventory(db, inv4, userId, "EQUIPMENT", pan.getLong("_id"), 1.0, "COUNT");

            long ki1 = generateSequence(db, "kitchen_inventory_sequence");
            upsertKitchenInventory(db, ki1, kitchenId, inv1);

            long ki2 = generateSequence(db, "kitchen_inventory_sequence");
            upsertKitchenInventory(db, ki2, kitchenId, inv2);

            long ki3 = generateSequence(db, "kitchen_inventory_sequence");
            upsertKitchenInventory(db, ki3, kitchenId, inv3);

            long ki4 = generateSequence(db, "kitchen_inventory_sequence");
            upsertKitchenInventory(db, ki4, kitchenId, inv4);

            System.out.println("Preset data creation completed.");
        }
    }

    private static String resolveMongoUri() {
        // prefer environment variable
        String env = System.getenv("SPRING_DATA_MONGODB_URI");
        if (env != null && !env.isBlank()) return env;
        env = System.getenv("MONGODB_URI");
        if (env != null && !env.isBlank()) return env;

        // fallback to application.properties bundled in resources
        try (InputStream in = PresetUp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String uri = p.getProperty("spring.data.mongodb.uri");
                if (uri != null && !uri.isBlank()) return uri;
            }
        } catch (IOException ignored) { }
        throw new IllegalStateException("MongoDB URI not found. Set SPRING_DATA_MONGODB_URI or MONGODB_URI environment variable or provide application.properties in classpath.");
    }

    private static String getDatabaseNameFromUri(String uri) {
        // naive parse for mongodb+srv://.../DatabaseName
        int idx = uri.lastIndexOf('/');
        if (idx > -1 && idx + 1 < uri.length()) {
            String tail = uri.substring(idx + 1);
            int q = tail.indexOf('?');
            return q > -1 ? tail.substring(0, q) : tail;
        }
        return "test";
    }

    private static long generateSequence(MongoDatabase db, String seqName) {
        MongoCollection<Document> seqCol = db.getCollection("database_sequences");
        Document updated = seqCol.findOneAndUpdate(
                new Document("_id", seqName),
                new Document("$inc", new Document("seq", 1)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        );
        if (updated == null) return 1L;
        Number n = (Number) updated.get("seq");
        return n == null ? 1L : n.longValue();
    }

    private static Document upsertUser(MongoDatabase db, long id, String name, String email) {
        MongoCollection<Document> col = db.getCollection("users");
        Document existing = col.find(new Document("email", email)).first();
        if (existing != null) return existing;
        Document doc = new Document("_id", id)
                .append("name", name)
                .append("email", email)
                .append("passwordHash", "preset-demo-password")
                .append("createdAt", Date.from(Instant.now()))
                .append("updatedAt", Date.from(Instant.now()))
                .append("status", "ACTIVE");
        col.insertOne(doc);
        return doc;
    }

    private static Document upsertKitchen(MongoDatabase db, long id, String name, long ownerId) {
        MongoCollection<Document> col = db.getCollection("kitchen");
        Document existing = col.find(new Document("name", name).append("ownerId", ownerId)).first();
        if (existing != null) return existing;
        Document doc = new Document("_id", id)
                .append("name", name)
                .append("ownerId", ownerId)
                .append("createdAt", Date.from(Instant.now()))
                .append("updatedAt", Date.from(Instant.now()));
        col.insertOne(doc);
        return doc;
    }

    private static Document upsertEquipment(MongoDatabase db, long id, String name, String description) {
        MongoCollection<Document> col = db.getCollection("equipment");
        Document existing = col.find(new Document("name", name)).first();
        if (existing != null) return existing;
        Document doc = new Document("_id", id)
                .append("name", name)
                .append("description", description)
                .append("createdAt", Date.from(Instant.now()))
                .append("updatedAt", Date.from(Instant.now()));
        col.insertOne(doc);
        return doc;
    }

    private static Document upsertIngredient(MongoDatabase db, long id, String name, String description, String unit) {
        MongoCollection<Document> col = db.getCollection("ingredients");
        Document existing = col.find(new Document("name", name)).first();
        if (existing != null) return existing;
        Document doc = new Document("_id", id)
                .append("name", name)
                .append("description", description)
                .append("defaultUnit", unit)
                .append("createdAt", Date.from(Instant.now()))
                .append("updatedAt", Date.from(Instant.now()));
        col.insertOne(doc);
        return doc;
    }

    private static Document upsertInventory(MongoDatabase db, long id, long userId, String itemType, long itemId, double quantity, String unit) {
        MongoCollection<Document> col = db.getCollection("inventory");
        Document existing = col.find(new Document("userId", userId).append("itemType", itemType).append("itemId", itemId)).first();
        if (existing != null) {
            col.updateOne(existing, new Document("$set", new Document("quantity", quantity).append("unit", unit).append("lastUpdated", Date.from(Instant.now()))));
            return col.find(new Document("_id", existing.getLong("_id"))).first();
        }
        Document doc = new Document("_id", id)
                .append("userId", userId)
                .append("itemType", itemType)
                .append("itemId", itemId)
                .append("quantity", quantity)
                .append("unit", unit)
                .append("lastUpdated", Date.from(Instant.now()));
        col.insertOne(doc);
        return doc;
    }

    private static Document upsertKitchenInventory(MongoDatabase db, long id, long kitchenId, long inventoryId) {
        MongoCollection<Document> col = db.getCollection("kitchen_inventory");
        Document existing = col.find(new Document("kitchenId", kitchenId).append("inventoryId", inventoryId)).first();
        if (existing != null) return existing;
        Document doc = new Document("_id", id)
                .append("kitchenId", kitchenId)
                .append("inventoryId", inventoryId);
        col.insertOne(doc);
        return doc;
    }
}
