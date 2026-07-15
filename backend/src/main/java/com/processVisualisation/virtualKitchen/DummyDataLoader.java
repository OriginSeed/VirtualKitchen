package com.processVisualisation.virtualKitchen;

import com.processVisualisation.virtualKitchen.model.Equipment;
import com.processVisualisation.virtualKitchen.model.Ingredient;
import com.processVisualisation.virtualKitchen.model.Inventory;
import com.processVisualisation.virtualKitchen.model.ItemType;
import com.processVisualisation.virtualKitchen.model.Kitchen;
import com.processVisualisation.virtualKitchen.model.KitchenInventory;
import com.processVisualisation.virtualKitchen.model.UnitType;
import com.processVisualisation.virtualKitchen.model.User;
import com.processVisualisation.virtualKitchen.repository.EquipmentRepository;
import com.processVisualisation.virtualKitchen.repository.IngredientRepository;
import com.processVisualisation.virtualKitchen.repository.InventoryRepository;
import com.processVisualisation.virtualKitchen.repository.KitchenInventoryRepository;
import com.processVisualisation.virtualKitchen.repository.KitchenRepository;
import com.processVisualisation.virtualKitchen.repository.UserRepository;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Helper loader: this class no longer runs automatically at application startup.
 * Instantiate it and call {@link #runLoader(Long, String, String, String)} from a manual runner
 * (for example, the `PresetUp` utility provided alongside this project).
 */
@Component
public class DummyDataLoader implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DummyDataLoader.class);

    public static final Long DEFAULT_USER_ID = null;
    public static final String DEFAULT_USER_EMAIL = "demo.user@example.com";
    public static final String DEFAULT_USER_NAME = "Demo User";
    public static final String DEFAULT_KITCHEN_NAME = "Demo Kitchen";

    private final UserRepository userRepository;
    private final KitchenRepository kitchenRepository;
    private final EquipmentRepository equipmentRepository;
    private final IngredientRepository ingredientRepository;
    private final InventoryRepository inventoryRepository;
    private final KitchenInventoryRepository kitchenInventoryRepository;
    private final SequenceGeneratorService sequenceGeneratorService;

    public DummyDataLoader(
            UserRepository userRepository,
            KitchenRepository kitchenRepository,
            EquipmentRepository equipmentRepository,
            IngredientRepository ingredientRepository,
            InventoryRepository inventoryRepository,
            KitchenInventoryRepository kitchenInventoryRepository,
            SequenceGeneratorService sequenceGeneratorService
    ) {
        this.userRepository = userRepository;
        this.kitchenRepository = kitchenRepository;
        this.equipmentRepository = equipmentRepository;
        this.ingredientRepository = ingredientRepository;
        this.inventoryRepository = inventoryRepository;
        this.kitchenInventoryRepository = kitchenInventoryRepository;
        this.sequenceGeneratorService = sequenceGeneratorService;
    }

    public void runLoader(Long targetUserId, String targetUserEmail, String targetUserName, String targetKitchenName) {
        Long userId = targetUserId != null ? targetUserId : DEFAULT_USER_ID;
        String userEmail = targetUserEmail != null ? targetUserEmail : DEFAULT_USER_EMAIL;
        String userName = targetUserName != null ? targetUserName : DEFAULT_USER_NAME;
        String kitchenName = targetKitchenName != null ? targetKitchenName : DEFAULT_KITCHEN_NAME;

        User user = resolveTargetUser(userId, userEmail, userName);
        Kitchen kitchen = resolveOrCreateKitchen(user, kitchenName);

        Equipment kettle = resolveOrCreateEquipment("Electric Kettle", "1.7 liter kettle for boiling water");
        Equipment fryingPan = resolveOrCreateEquipment("Nonstick Frying Pan", "12-inch pan for cooking and searing");

        Ingredient flour = resolveOrCreateIngredient("All-purpose Flour", "Standard cooking flour", UnitType.KG);
        Ingredient salt = resolveOrCreateIngredient("Sea Salt", "Fine sea salt", UnitType.GRAM);
        Ingredient oliveOil = resolveOrCreateIngredient("Olive Oil", "Extra virgin olive oil", UnitType.LITER);

        Inventory flourInventory = resolveOrCreateInventory(user.getId(), ItemType.INGREDIENT, flour.getId(), 2.0, UnitType.KG);
        Inventory saltInventory = resolveOrCreateInventory(user.getId(), ItemType.INGREDIENT, salt.getId(), 500.0, UnitType.GRAM);
        Inventory kettleInventory = resolveOrCreateInventory(user.getId(), ItemType.EQUIPMENT, kettle.getId(), 1.0, UnitType.COUNT);
        Inventory panInventory = resolveOrCreateInventory(user.getId(), ItemType.EQUIPMENT, fryingPan.getId(), 1.0, UnitType.COUNT);

        resolveOrCreateKitchenInventory(kitchen.getId(), flourInventory.getId());
        resolveOrCreateKitchenInventory(kitchen.getId(), saltInventory.getId());
        resolveOrCreateKitchenInventory(kitchen.getId(), kettleInventory.getId());
        resolveOrCreateKitchenInventory(kitchen.getId(), panInventory.getId());

        logger.info("Dummy data loader completed for user {} with kitchen {}", user.getEmail(), kitchen.getName());
    }

    private User resolveTargetUser(Long targetUserId, String targetUserEmail, String targetUserName) {
        if (targetUserId != null) {
            Optional<User> byId = userRepository.findById(targetUserId);
            if (byId.isPresent()) {
                return byId.get();
            }
        }

        if (targetUserEmail != null && !targetUserEmail.isBlank()) {
            Optional<User> byEmail = userRepository.findByEmail(targetUserEmail);
            if (byEmail.isPresent()) {
                return byEmail.get();
            }
        }

        User user = new User();
        user.setId(sequenceGeneratorService.generateSequence(User.SEQUENCE_NAME));
        user.setName(targetUserName);
        user.setEmail(targetUserEmail);
        user.setPasswordHash("demo-password-hash");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private Kitchen resolveOrCreateKitchen(User user, String kitchenName) {
        Optional<Kitchen> existingKitchen = kitchenRepository.findByOwnerId(user.getId()).stream().findFirst();
        if (existingKitchen.isPresent()) {
            return existingKitchen.get();
        }

        Kitchen kitchen = new Kitchen();
        kitchen.setId(sequenceGeneratorService.generateSequence(Kitchen.SEQUENCE_NAME));
        kitchen.setName(kitchenName);
        kitchen.setOwnerId(user.getId());
        kitchen.setCreatedAt(LocalDateTime.now());
        kitchen.setUpdatedAt(LocalDateTime.now());
        return kitchenRepository.save(kitchen);
    }

    private Equipment resolveOrCreateEquipment(String name, String description) {
        Optional<Equipment> existing = equipmentRepository.findByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }

        Equipment equipment = new Equipment();
        equipment.setId(sequenceGeneratorService.generateSequence(Equipment.SEQUENCE_NAME));
        equipment.setName(name);
        equipment.setDescription(description);
        equipment.setCreatedAt(LocalDateTime.now());
        equipment.setUpdatedAt(LocalDateTime.now());
        return equipmentRepository.save(equipment);
    }

    private Ingredient resolveOrCreateIngredient(String name, String description, UnitType defaultUnit) {
        Optional<Ingredient> existing = ingredientRepository.findByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }

        Ingredient ingredient = new Ingredient();
        ingredient.setId(sequenceGeneratorService.generateSequence(Ingredient.SEQUENCE_NAME));
        ingredient.setName(name);
        ingredient.setDescription(description);
        ingredient.setDefaultUnit(defaultUnit);
        ingredient.setCreatedAt(LocalDateTime.now());
        ingredient.setUpdatedAt(LocalDateTime.now());
        return ingredientRepository.save(ingredient);
    }

    private Inventory resolveOrCreateInventory(Long userId, ItemType itemType, Long itemId, double quantity, UnitType unit) {
        Optional<Inventory> existing = inventoryRepository.findByUserIdAndItemTypeAndItemId(userId, itemType, itemId);
        if (existing.isPresent()) {
            Inventory inventory = existing.get();
            inventory.setQuantity(quantity);
            inventory.setUnit(unit);
            inventory.setLastUpdated(LocalDateTime.now());
            return inventoryRepository.save(inventory);
        }

        Inventory inventory = new Inventory();
        inventory.setId(sequenceGeneratorService.generateSequence(Inventory.SEQUENCE_NAME));
        inventory.setUserId(userId);
        inventory.setItemType(itemType);
        inventory.setItemId(itemId);
        inventory.setQuantity(quantity);
        inventory.setUnit(unit);
        inventory.setLastUpdated(LocalDateTime.now());
        return inventoryRepository.save(inventory);
    }

    private KitchenInventory resolveOrCreateKitchenInventory(Long kitchenId, Long inventoryId) {
        return kitchenInventoryRepository.findByKitchenId(kitchenId).stream()
                .filter(entry -> entry.getInventoryId().equals(inventoryId))
                .findFirst()
                .orElseGet(() -> {
                    KitchenInventory kitchenInventory = new KitchenInventory();
                    kitchenInventory.setId(sequenceGeneratorService.generateSequence(KitchenInventory.SEQUENCE_NAME));
                    kitchenInventory.setKitchenId(kitchenId);
                    kitchenInventory.setInventoryId(inventoryId);
                    return kitchenInventoryRepository.save(kitchenInventory);
                });
    }

    @Override
    public void run(String... args) throws Exception {
        runLoader(null,null,null,null);
    }
}
