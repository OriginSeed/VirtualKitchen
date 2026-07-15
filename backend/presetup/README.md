PresetUp - instructions

This utility creates demo data (user, kitchen, equipment, ingredients, inventory, kitchen_inventory) in the MongoDB used by the backend.

How it works
- `PresetUp` is a standalone Java class with a `main` method that connects directly to MongoDB using the same URI used by the backend.
- It creates sequences in `database_sequences` (same pattern as the application) and inserts documents into the collections:
  - `users`, `kitchen`, `equipment`, `ingredients`, `inventory`, `kitchen_inventory`.

How to run

Option A - from your IDE
- Build the project (Maven) and run the `com.processVisualisation.virtualKitchen.tools.PresetUp` main class.
- Ensure `SPRING_DATA_MONGODB_URI` or `MONGODB_URI` environment variable is set, or that `application.properties` contains `spring.data.mongodb.uri`.

Option B - using Maven (works because the project includes the spring-boot plugin which provides the runtime classpath):

```bash
cd backend
mvn -Dspring-boot.run.mainClass=com.processVisualisation.virtualKitchen.tools.PresetUp spring-boot:run
```

Notes
- The utility will not run automatically when the server starts. The previous `DummyDataLoader` was converted into a helper class (no longer annotated with `@Component`).
- Edit the constants in `PresetUp` if you want different default user/kitchen names or to read different env vars.

If you want the tool wired into a simple shell script or npm script, tell me which you prefer and I can add it.
