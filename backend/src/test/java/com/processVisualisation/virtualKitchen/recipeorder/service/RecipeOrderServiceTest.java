package com.processVisualisation.virtualKitchen.recipeorder.service;

import com.processVisualisation.virtualKitchen.auth.model.DeliveryAddress;
import com.processVisualisation.virtualKitchen.auth.model.User;
import com.processVisualisation.virtualKitchen.auth.repository.UserRepository;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.kitchen.model.Kitchen;
import com.processVisualisation.virtualKitchen.kitchen.repository.KitchenRepository;
import com.processVisualisation.virtualKitchen.recipe.model.NutritionInfo;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeThumbnailService;
import com.processVisualisation.virtualKitchen.recipeorder.config.RecipeOrderProperties;
import com.processVisualisation.virtualKitchen.recipeorder.dto.RecipeOrderRequests;
import com.processVisualisation.virtualKitchen.recipeorder.exception.RecipeOrderException;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.PaymentStatus;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrderStatus;
import com.processVisualisation.virtualKitchen.recipeorder.service.IngredientAvailabilityService.AvailabilityLine;
import com.processVisualisation.virtualKitchen.recipeorder.service.IngredientAvailabilityService.AvailabilityReport;
import com.processVisualisation.virtualKitchen.store.model.Ingredient;
import com.processVisualisation.virtualKitchen.store.repository.IngredientRepository;
import com.processVisualisation.virtualKitchen.store.units.UnitConversionService;
import com.processVisualisation.virtualKitchen.store.units.UnitType;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import static com.processVisualisation.virtualKitchen.recipeorder.service.RecipeOrderFixtures.*;
import static com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrderStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The recipe-order lifecycle end to end, against in-memory stores that keep the Mongo stores'
 * atomic / compare-and-set semantics. The recipe (base: 2 servings) needs 200 g flour, 250 ml milk
 * and 2 g salt per base batch, partly inside a subprocess; the kitchen stocks 1 kg flour, 1000 ml
 * milk and 100 g salt.
 */
class RecipeOrderServiceTest {

    private static final long USER = 7;
    private static final long OTHER_USER = 8;
    private static final long KITCHEN = 70;
    private static final long RECIPE = 1;
    private static final long FLOUR_ROW = 1001;
    private static final long MILK_ROW = 1002;
    private static final long SALT_ROW = 1003;

    private final InMemoryRecipeOrderStore orders = new InMemoryRecipeOrderStore();
    private InMemoryInventoryReservationStore inventory = new InMemoryInventoryReservationStore();
    private final RecipeTemplateRepository recipes = mock(RecipeTemplateRepository.class);
    private final ProcessRepository processes = mock(ProcessRepository.class);
    private final KitchenRepository kitchens = mock(KitchenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final IngredientRepository ingredients = mock(IngredientRepository.class);
    private final RecipeThumbnailService thumbnails = mock(RecipeThumbnailService.class);
    private final SequenceGeneratorService sequences = mock(SequenceGeneratorService.class);
    private final RecipeOrderProperties properties = new RecipeOrderProperties();
    private final MutableClock clock = new MutableClock();
    private final UnitConversionService conversions = UnitConversionService.withDefaults();
    private final Map<Long, Ingredient> catalog = catalog();
    private List<Process> liveProcesses;
    private RecipeTemplate recipe;
    private User user;
    private RecipeOrderService service;

    @BeforeEach
    void setUp() {
        AtomicLong ids = new AtomicLong(500);
        when(sequences.generateSequence(anyString())).thenAnswer(invocation -> ids.incrementAndGet());

        recipe = recipe(RECIPE, USER, Visibility.PRIVATE);
        when(recipes.findById(RECIPE)).thenAnswer(invocation -> Optional.of(recipe));
        liveProcesses = pancakeProcesses(RECIPE);
        when(processes.findByRecipeId(RECIPE)).thenAnswer(invocation -> liveProcesses);

        Kitchen kitchen = new Kitchen();
        kitchen.setId(KITCHEN);
        kitchen.setName("Ada's Virtual Kitchen");
        kitchen.setOwnerId(USER);
        when(kitchens.findByOwnerId(USER)).thenReturn(List.of(kitchen));

        user = new User();
        user.setId(USER);
        user.setName("Ada");
        when(users.findById(USER)).thenAnswer(invocation -> Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(ingredients.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> requested = invocation.getArgument(0);
            return StreamSupport.stream(requested.spliterator(), false).map(catalog::get).filter(java.util.Objects::nonNull).toList();
        });
        when(thumbnails.resolveAll(any())).thenReturn(Map.of());

        inventory.addRow(FLOUR_ROW, KITCHEN, USER, FLOUR, 1, UnitType.KG);
        inventory.addRow(MILK_ROW, KITCHEN, USER, MILK, 1000, UnitType.ML);
        inventory.addRow(SALT_ROW, KITCHEN, USER, SALT, 100, UnitType.GRAM);
        service = newService(inventory);
    }

    private RecipeOrderService newService(com.processVisualisation.virtualKitchen.kitchen.reservation.InventoryReservationStore store) {
        return new RecipeOrderService(orders, store, new IngredientAvailabilityService(store, ingredients, conversions),
                recipes, processes, kitchens, users, ingredients, thumbnails, sequences, conversions, properties, clock);
    }

    // --- recipe order ---

    @Test
    void ordersOwnRecipeThroughTheWholeLifecycle() {
        RecipeOrder order = createConfirmed(4); // 2x the base recipe

        assertEquals(AWAITING_INGREDIENTS, order.getStatus());
        assertEquals("RO-000501", order.getOrderCode());
        assertEquals(2.0, order.getScale(), 1e-9);
        AvailabilityReport report = service.availability(USER, order.getId());
        assertTrue(report.canReserve());
        assertLine(report, FLOUR, 0.4, 1, 0);
        assertLine(report, MILK, 0.5, 1, 0);   // 1000 ml compared as 1 l
        assertLine(report, SALT, 4, 100, 0);
        assertEquals(1, inventory.row(FLOUR_ROW).getQuantity(), 1e-9, "a read-only check never changes stock");

        order = service.reserve(USER, order.getId());
        assertEquals(AWAITING_PAYMENT, order.getStatus());
        assertEquals(0.4, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(500, inventory.row(MILK_ROW).getReservedQuantity(), 1e-9, "held in the row's own unit");
        assertEquals(1, inventory.row(FLOUR_ROW).getQuantity(), 1e-9, "reserving doesn't consume yet");

        order = service.pay(USER, order.getId(), new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "pay-1"));
        assertEquals(CONFIRMED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPayment().getStatus());
        assertTrue(order.getPayment().getReference().startsWith("DEMO-PAY-"));
        assertEquals(78, order.getPayment().getAmount(), 1e-9, "the server's demo fees, not a client amount");
        assertEquals("INR", order.getPayment().getCurrency());

        order = advanceToEnd(order);

        assertEquals(COMPLETED, order.getStatus());
        assertEquals(0.6, inventory.row(FLOUR_ROW).getQuantity(), 1e-9);
        assertEquals(0, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(500, inventory.row(MILK_ROW).getQuantity(), 1e-9);
        assertEquals(96, inventory.row(SALT_ROW).getQuantity(), 1e-9);
        RecipeOrder.Timestamps times = order.getTimestamps();
        assertNotNull(times.getPreparationStartedAt());
        assertNotNull(times.getQualityCheckAt());
        assertNotNull(times.getOutForDeliveryAt());
        assertNotNull(times.getDeliveredAt());
        assertNotNull(times.getCompletedAt());
        assertTrue(order.getAllocations().stream().allMatch(a -> a.getState() == RecipeOrder.AllocationState.CONSUMED));

        // Persisted: a fresh read (a reload of the app) still shows the completed order.
        assertEquals(COMPLETED, service.get(USER, order.getId()).getStatus());
        assertEquals(1, service.listMine(USER).size());
    }

    @Test
    void preparationProgressIsPersistedStepByStep() {
        RecipeOrder order = paidOrder(2);
        order = service.advance(USER, order.getId(), new RecipeOrderRequests.Advance(CONFIRMED, null));
        assertEquals(PREPARING, order.getStatus());
        assertEquals(0, order.getCurrentStepIndex());
        order = service.advance(USER, order.getId(), new RecipeOrderRequests.Advance(PREPARING, 0));

        RecipeOrder stored = service.get(USER, order.getId());
        assertEquals(PREPARING, stored.getStatus());
        assertEquals(1, stored.getCurrentStepIndex());
        assertTrue(stored.getEvents().stream().anyMatch(e -> "STEP_STARTED".equals(e.getType()) && e.isSimulated()));
    }

    @Test
    void cannotOrderAnotherUsersPrivateRecipe() {
        recipe = recipe(RECIPE, OTHER_USER, Visibility.PRIVATE);
        RecipeOrderException error = assertThrows(RecipeOrderException.class,
                () -> service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, address(), null)));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void anotherUsersPublicRecipeMustBeCopiedFirst() {
        recipe = recipe(RECIPE, OTHER_USER, Visibility.PUBLIC);
        RecipeOrderException error = assertThrows(RecipeOrderException.class,
                () -> service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, address(), null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
    }

    @Test
    void snapshotStaysStableWhenTheSourceRecipeChanges() {
        RecipeOrder order = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, address(), null));

        // The owner edits the live recipe after ordering: 5 kg of flour, renamed.
        @SuppressWarnings("unchecked")
        Map<String, Object> flourLine = (Map<String, Object>) ((List<?>) ((Map<?, ?>) ((Map<?, ?>) liveProcesses.get(1)
                .getNodes().get(0).getData().get("step")).get("actionOn")).get("ingredients")).get(0);
        flourLine.put("quantity", 5000);
        recipe.setName("Renamed");

        order = service.confirm(USER, order.getId(), new RecipeOrderRequests.Confirm(false));
        assertEquals("Pancakes", order.getRecipeSnapshot().getName());
        assertEquals(0.2, requirement(order, FLOUR).getRequiredQuantity(), 1e-9);
    }

    @Test
    void rejectsInvalidServings() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 0, address(), null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 51, address(), null)));
        RecipeOrder order = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, address(), null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.updateDetails(USER, order.getId(), new RecipeOrderRequests.Update(-3, null, null)));
    }

    @Test
    void changingServingsRequiresConfirmingAgain() {
        RecipeOrder order = createConfirmed(2);
        order = service.updateDetails(USER, order.getId(), new RecipeOrderRequests.Update(6, null, null));
        assertEquals(DRAFT, order.getStatus());
        assertTrue(order.getRequirements().isEmpty());
        order = service.confirm(USER, order.getId(), new RecipeOrderRequests.Confirm(false));
        assertEquals(0.6, requirement(order, FLOUR).getRequiredQuantity(), 1e-9);
    }

    @Test
    void confirmationNeedsACompleteAddressAndCanSaveItAsDefault() {
        RecipeOrder order = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, null, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.confirm(USER, order.getId(), new RecipeOrderRequests.Confirm(false)));

        service.updateDetails(USER, order.getId(), new RecipeOrderRequests.Update(null, address(), "Less salt please"));
        service.confirm(USER, order.getId(), new RecipeOrderRequests.Confirm(true));
        assertEquals("221B Baker Street", user.getDeliveryAddress().getLine1());

        RecipeOrder next = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, null, null));
        assertEquals("221B Baker Street", next.getDeliveryAddress().getLine1(), "prefilled from the saved default");
    }

    // --- inventory ---

    @Test
    void insufficientInventoryReportsExactShortagesAndHoldsNothing() {
        inventory = new InMemoryInventoryReservationStore();
        inventory.addRow(FLOUR_ROW, KITCHEN, USER, FLOUR, 100, UnitType.GRAM); // 0.1 kg
        inventory.addRow(MILK_ROW, KITCHEN, USER, MILK, 1, UnitType.LITER);
        inventory.addRow(SALT_ROW, KITCHEN, USER, SALT, 100, UnitType.GRAM);
        service = newService(inventory);
        RecipeOrder order = createConfirmed(4);

        AvailabilityReport report = service.availability(USER, order.getId());
        assertFalse(report.canReserve());
        AvailabilityLine flour = assertLine(report, FLOUR, 0.4, 0.1, 0.3);
        assertEquals(1, flour.purchase().quantity(), 1e-9, "the shop sells whole kilograms");
        assertEquals(UnitType.KG, flour.purchase().unit());
        assertEquals(0.3, flour.purchase().neededQuantity(), 1e-9, "needed for the recipe vs. sold");
        assertTrue(report.lines().stream().filter(line -> line.ingredientId() != FLOUR).allMatch(AvailabilityLine::sufficient));

        RecipeOrderException error = assertThrows(RecipeOrderException.class, () -> service.reserve(USER, order.getId()));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("Flour"));
        assertEquals(AWAITING_INGREDIENTS, service.get(USER, order.getId()).getStatus());
        assertEquals(0, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(0, inventory.row(MILK_ROW).getReservedQuantity(), 1e-9);
    }

    @Test
    void stockBoughtLaterIsSeenOnTheNextCheck() {
        inventory = new InMemoryInventoryReservationStore();
        inventory.addRow(MILK_ROW, KITCHEN, USER, MILK, 1, UnitType.LITER);
        inventory.addRow(SALT_ROW, KITCHEN, USER, SALT, 100, UnitType.GRAM);
        service = newService(inventory);
        RecipeOrder order = createConfirmed(2);
        assertFalse(service.availability(USER, order.getId()).canReserve());

        // The shop checkout adds a legacy user-scoped row (no kitchen yet); it still counts.
        inventory.addRow(FLOUR_ROW, null, USER, FLOUR, 1, UnitType.KG);
        assertTrue(service.availability(USER, order.getId()).canReserve());
        assertEquals(AWAITING_PAYMENT, service.reserve(USER, order.getId()).getStatus());
    }

    @Test
    void concurrentReservationsCannotOverAllocateStock() throws Exception {
        inventory = new InMemoryInventoryReservationStore();
        inventory.addRow(FLOUR_ROW, KITCHEN, USER, FLOUR, 1.3, UnitType.KG);   // enough for 3 orders of 0.4 kg
        inventory.addRow(MILK_ROW, KITCHEN, USER, MILK, 100, UnitType.LITER);
        inventory.addRow(SALT_ROW, KITCHEN, USER, SALT, 10_000, UnitType.GRAM);
        service = newService(inventory);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) orderIds.add(createConfirmed(4).getId());

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RecipeOrderStatus>> results = new ArrayList<>();
        for (Long id : orderIds) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return service.reserve(USER, id).getStatus();
                } catch (RecipeOrderException e) {
                    return service.get(USER, id).getStatus();
                }
            }));
        }
        start.countDown();
        int reserved = 0;
        for (Future<RecipeOrderStatus> result : results) {
            if (result.get(10, TimeUnit.SECONDS) == AWAITING_PAYMENT) reserved++;
        }
        pool.shutdown();

        assertEquals(3, reserved);
        assertEquals(1.2, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertTrue(inventory.row(FLOUR_ROW).getReservedQuantity() <= inventory.row(FLOUR_ROW).getQuantity());
    }

    @Test
    void stockTakenBetweenCheckAndReservationEndsInConflictWithNothingHeld() {
        AtomicBoolean raced = new AtomicBoolean();
        InMemoryInventoryReservationStore racing = new InMemoryInventoryReservationStore() {
            @Override
            public synchronized com.processVisualisation.virtualKitchen.kitchen.reservation.InventoryReservationStore.Outcome reserve(
                    Long inventoryId, double amount, String reserveKey) {
                // Another order grabs most of the milk right after this order planned its reservation.
                if (inventoryId == MILK_ROW && raced.compareAndSet(false, true)) super.reserve(MILK_ROW, 900, "other-order");
                return super.reserve(inventoryId, amount, reserveKey);
            }
        };
        racing.addRow(FLOUR_ROW, KITCHEN, USER, FLOUR, 1, UnitType.KG);
        racing.addRow(MILK_ROW, KITCHEN, USER, MILK, 1000, UnitType.ML);
        racing.addRow(SALT_ROW, KITCHEN, USER, SALT, 100, UnitType.GRAM);
        inventory = racing;
        service = newService(racing);
        RecipeOrder order = createConfirmed(2);

        order = service.reserve(USER, order.getId());

        assertEquals(INVENTORY_CONFLICT, order.getStatus());
        assertEquals(0, racing.row(FLOUR_ROW).getReservedQuantity(), 1e-9, "the flour it had already held is released");
        assertEquals(900, racing.row(MILK_ROW).getReservedQuantity(), 1e-9, "only the other order's hold remains");
        assertTrue(order.getAllocations().isEmpty());
    }

    @Test
    void declinedPaymentReleasesTheReservationAndCanBeRetried() {
        RecipeOrder order = service.reserve(USER, createConfirmed(2).getId());
        long id = order.getId();
        assertEquals(0.2, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);

        order = service.pay(USER, id, new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_DECLINE, "pay-1"));

        assertEquals(PAYMENT_FAILED, order.getStatus());
        assertEquals(PaymentStatus.FAILED, order.getPayment().getStatus());
        assertEquals(0, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(1, inventory.row(FLOUR_ROW).getQuantity(), 1e-9);
        assertFalse(order.getStatus().isPaid());
        assertStatus(HttpStatus.CONFLICT, () -> service.advance(USER, id, null));

        order = service.reserve(USER, order.getId());
        order = service.pay(USER, order.getId(), new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "pay-2"));
        assertEquals(CONFIRMED, order.getStatus());
        assertEquals(2, order.getPaymentAttempts().size());
        assertEquals(0.2, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
    }

    @Test
    void cancellingReleasesHeldStock() {
        RecipeOrder order = service.reserve(USER, createConfirmed(2).getId());
        order = service.cancel(USER, order.getId());
        assertEquals(CANCELLED, order.getStatus());
        assertEquals(0, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(1, inventory.row(FLOUR_ROW).getQuantity(), 1e-9);
        assertEquals(CANCELLED, service.cancel(USER, order.getId()).getStatus(), "cancelling twice is harmless");
    }

    @Test
    void cancellingAPaidOrderBeforePreparationRefundsAndReleases() {
        RecipeOrder order = paidOrder(2);
        order = service.cancel(USER, order.getId());
        assertEquals(CANCELLED, order.getStatus());
        assertEquals(PaymentStatus.REFUNDED, order.getPayment().getStatus());
        assertEquals(0, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
    }

    @Test
    void ordersInPreparationCannotBeCancelled() {
        RecipeOrder order = paidOrder(2);
        service.advance(USER, order.getId(), null);
        assertStatus(HttpStatus.CONFLICT, () -> service.cancel(USER, order.getId()));
        assertEquals(0.2, inventory.row(FLOUR_ROW).getReservedQuantity(), 1e-9, "still held for the chef");
    }

    @Test
    void completionConsumesExactlyOnceEvenWhenRetriedAfterAFailure() {
        AtomicInteger consumeCalls = new AtomicInteger();
        InMemoryInventoryReservationStore flaky = new InMemoryInventoryReservationStore() {
            @Override
            public synchronized com.processVisualisation.virtualKitchen.kitchen.reservation.InventoryReservationStore.Outcome consume(
                    Long inventoryId, double amount, String reserveKey, String releaseKey, String consumeKey) {
                // The first completion attempt dies after consuming one row.
                if (consumeCalls.incrementAndGet() == 2) throw new IllegalStateException("database unavailable");
                return super.consume(inventoryId, amount, reserveKey, releaseKey, consumeKey);
            }
        };
        flaky.addRow(FLOUR_ROW, KITCHEN, USER, FLOUR, 1, UnitType.KG);
        flaky.addRow(MILK_ROW, KITCHEN, USER, MILK, 1000, UnitType.ML);
        flaky.addRow(SALT_ROW, KITCHEN, USER, SALT, 100, UnitType.GRAM);
        inventory = flaky;
        service = newService(flaky);
        RecipeOrder order = paidOrder(2);
        while (order.getStatus() != OUT_FOR_DELIVERY) order = service.advance(USER, order.getId(), null);
        long id = order.getId();

        assertThrows(IllegalStateException.class, () -> service.advance(USER, id, new RecipeOrderRequests.Advance(OUT_FOR_DELIVERY, null)));
        assertEquals(COMPLETING, service.get(USER, id).getStatus(), "delivered, consumption still being finalised");

        order = service.advance(USER, id, null);   // retry resumes
        assertEquals(COMPLETED, order.getStatus());
        order = service.get(USER, id);
        assertStatus(HttpStatus.CONFLICT, () -> service.advance(USER, id, null));

        assertEquals(0.8, flaky.row(FLOUR_ROW).getQuantity(), 1e-9, "flour deducted once");
        assertEquals(750, flaky.row(MILK_ROW).getQuantity(), 1e-9, "milk deducted once");
        assertEquals(98, flaky.row(SALT_ROW).getQuantity(), 1e-9, "salt deducted once");
        assertEquals(0, flaky.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(COMPLETED, order.getStatus());
    }

    @Test
    void anInterruptedReservationIsRecoveredWithoutLeakingStock() {
        AtomicInteger reserveCalls = new AtomicInteger();
        InMemoryInventoryReservationStore crashing = new InMemoryInventoryReservationStore() {
            @Override
            public synchronized com.processVisualisation.virtualKitchen.kitchen.reservation.InventoryReservationStore.Outcome reserve(
                    Long inventoryId, double amount, String reserveKey) {
                if (reserveCalls.incrementAndGet() == 2) throw new IllegalStateException("connection reset");
                return super.reserve(inventoryId, amount, reserveKey);
            }
        };
        crashing.addRow(FLOUR_ROW, KITCHEN, USER, FLOUR, 1, UnitType.KG);
        crashing.addRow(MILK_ROW, KITCHEN, USER, MILK, 1000, UnitType.ML);
        crashing.addRow(SALT_ROW, KITCHEN, USER, SALT, 100, UnitType.GRAM);
        service = newService(crashing);
        long id = createConfirmed(2).getId();

        assertThrows(IllegalStateException.class, () -> service.reserve(USER, id));
        assertEquals(RESERVING, service.get(USER, id).getStatus());
        // Requirements (and so allocations) follow the recipe: salt (MAIN) first, then flour and milk.
        assertEquals(2, crashing.row(SALT_ROW).getReservedQuantity(), 1e-9, "held by the interrupted attempt");
        assertEquals(0, crashing.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertStatus(HttpStatus.CONFLICT, () -> service.reserve(USER, id));   // still in progress

        clock.advance(Duration.ofMinutes(5));
        RecipeOrder order = service.reserve(USER, id);   // recovers, then reserves afresh

        assertEquals(AWAITING_PAYMENT, order.getStatus());
        assertEquals(2, crashing.row(SALT_ROW).getReservedQuantity(), 1e-9, "the old hold was released, the new one taken");
        assertEquals(0.2, crashing.row(FLOUR_ROW).getReservedQuantity(), 1e-9);
        assertEquals(250, crashing.row(MILK_ROW).getReservedQuantity(), 1e-9);
    }

    @Test
    void recipesWithUncheckableIngredientsCannotBeReserved() {
        Map<String, Object> custom = line("custom", 1, "piece");
        custom.put("customIngredientName", "Saffron threads");
        append(liveProcesses.get(0), step("m4", List.of(custom)));
        RecipeOrder order = createConfirmed(2);

        assertEquals(1, order.getRequirementIssues().size());
        assertFalse(service.availability(USER, order.getId()).canReserve());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.reserve(USER, order.getId()));
    }

    @Test
    void recipesWhoseStructureIsNotReadyCannotBeOrdered() {
        // A main step that nothing connects to: the kitchen couldn't tell when to do it.
        liveProcesses.get(0).getNodes().add(step("stray", List.of(line(SALT, 1, "g"))));

        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, null, null)));
    }

    // --- payment and lifecycle ---

    @Test
    void repeatedPaymentRequestsChargeOnce() {
        RecipeOrder order = service.reserve(USER, createConfirmed(2).getId());
        RecipeOrderRequests.Pay pay = new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "same-key");
        service.pay(USER, order.getId(), pay);
        RecipeOrder again = service.pay(USER, order.getId(), pay);

        assertEquals(CONFIRMED, again.getStatus());
        assertEquals(1, again.getPaymentAttempts().size());
        assertStatus(HttpStatus.CONFLICT, () -> service.pay(USER, order.getId(),
                new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "another-key")));
        assertEquals(1, service.listMine(USER).size(), "no duplicate order");
    }

    @Test
    void clientsCannotPickAnArbitraryPaymentResult() {
        RecipeOrder order = service.reserve(USER, createConfirmed(2).getId());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.pay(USER, order.getId(), new RecipeOrderRequests.Pay("PAID", "k")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.pay(USER, order.getId(), new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, " ")));
        properties.setDemoPaymentEnabled(false);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.pay(USER, order.getId(),
                new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "k")));
        assertEquals(AWAITING_PAYMENT, service.get(USER, order.getId()).getStatus());
    }

    @Test
    void unpaidOrdersCannotProgressTowardsCompletion() {
        RecipeOrder draft = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, address(), null));
        assertStatus(HttpStatus.CONFLICT, () -> service.advance(USER, draft.getId(), null));
        service.confirm(USER, draft.getId(), new RecipeOrderRequests.Confirm(false));
        assertStatus(HttpStatus.CONFLICT, () -> service.advance(USER, draft.getId(), null));
        service.reserve(USER, draft.getId());
        assertStatus(HttpStatus.CONFLICT, () -> service.advance(USER, draft.getId(), null));
        assertEquals(AWAITING_PAYMENT, service.get(USER, draft.getId()).getStatus());
        assertEquals(1, inventory.row(FLOUR_ROW).getQuantity(), 1e-9, "nothing consumed");
    }

    @Test
    void rejectsActionsTheCurrentStatusDoesNotAllow() {
        RecipeOrder draft = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, 2, address(), null));
        assertStatus(HttpStatus.CONFLICT, () -> service.reserve(USER, draft.getId()));
        assertStatus(HttpStatus.CONFLICT, () -> service.availability(USER, draft.getId()));
        service.confirm(USER, draft.getId(), new RecipeOrderRequests.Confirm(false));
        assertStatus(HttpStatus.CONFLICT, () -> service.confirm(USER, draft.getId(), new RecipeOrderRequests.Confirm(false)));
        assertStatus(HttpStatus.CONFLICT, () -> service.pay(USER, draft.getId(),
                new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "k")));
    }

    @Test
    void aStaleAdvanceNeverSkipsAStage() {
        RecipeOrder order = paidOrder(2);
        service.advance(USER, order.getId(), new RecipeOrderRequests.Advance(CONFIRMED, null));
        // A second click (or tab) still showing CONFIRMED:
        assertStatus(HttpStatus.CONFLICT, () -> service.advance(USER, order.getId(), new RecipeOrderRequests.Advance(CONFIRMED, null)));
        RecipeOrder stored = service.get(USER, order.getId());
        assertEquals(PREPARING, stored.getStatus());
        assertEquals(0, stored.getCurrentStepIndex());
    }

    // --- logging ---

    @Test
    void cancellingLogsOneEventWithIdsAndStatusOnly() {
        RecipeOrder order = service.reserve(USER, createConfirmed(2).getId());

        try (LogCapture logs = LogCapture.of(RecipeOrderService.class)) {
            service.cancel(USER, order.getId());

            List<ILoggingEvent> cancelled = logs.events("recipe_order_cancelled");
            assertEquals(1, cancelled.size(), logs.allMessages());
            assertEquals(Level.INFO, cancelled.get(0).getLevel());
            String message = cancelled.get(0).getFormattedMessage();
            assertTrue(message.contains("orderId=" + order.getId()), message);
            assertTrue(message.contains("fromStatus=AWAITING_PAYMENT"), message);
            assertTrue(message.contains("status=CANCELLED"), message);
            assertTrue(message.contains("refunded=false"), message);
            assertFalse(logs.allMessages().contains("Pancakes"), "no recipe text in the logs");
            assertFalse(logs.allMessages().contains("Baker Street"), "no address in the logs");
        }
    }

    // --- security ---

    @Test
    void otherUsersCannotSeeOrChangeAnOrder() {
        RecipeOrder order = service.reserve(USER, createConfirmed(2).getId());
        long id = order.getId();
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(OTHER_USER, id));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.availability(OTHER_USER, id));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.pay(OTHER_USER, id, new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "k")));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.cancel(OTHER_USER, id));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.advance(OTHER_USER, id, null));
        assertTrue(service.listMine(OTHER_USER).isEmpty());
        assertEquals(USER, service.get(USER, id).getUserId());
        assertEquals(AWAITING_PAYMENT, service.get(USER, id).getStatus());
    }

    // --- helpers ---

    private RecipeOrder createConfirmed(int servings) {
        RecipeOrder order = service.createDraft(USER, new RecipeOrderRequests.Create(RECIPE, servings, address(), null));
        return service.confirm(USER, order.getId(), new RecipeOrderRequests.Confirm(false));
    }

    private RecipeOrder paidOrder(int servings) {
        RecipeOrder order = service.reserve(USER, createConfirmed(servings).getId());
        return service.pay(USER, order.getId(), new RecipeOrderRequests.Pay(RecipeOrderService.DEMO_APPROVE, "pay-" + order.getId()));
    }

    private RecipeOrder advanceToEnd(RecipeOrder order) {
        int guard = 0;
        while (order.getStatus() != COMPLETED) {
            order = service.advance(USER, order.getId(), new RecipeOrderRequests.Advance(order.getStatus(), order.getCurrentStepIndex()));
            if (++guard > 50) throw new AssertionError("never completed");
        }
        return order;
    }

    private static RecipeOrder.IngredientRequirement requirement(RecipeOrder order, long ingredientId) {
        return order.getRequirements().stream().filter(r -> r.getIngredientId() == ingredientId).findFirst().orElseThrow();
    }

    private static AvailabilityLine assertLine(AvailabilityReport report, long ingredientId, double required, double available, double missing) {
        AvailabilityLine line = report.lines().stream().filter(l -> l.ingredientId() == ingredientId).findFirst().orElseThrow();
        assertEquals(required, line.required(), 1e-9, line.name() + " required");
        assertEquals(available, line.available(), 1e-9, line.name() + " available");
        assertEquals(missing, line.missing(), 1e-9, line.name() + " missing");
        assertEquals(missing == 0, line.sufficient());
        if (missing == 0) assertNull(line.purchase());
        return line;
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        RecipeOrderException error = assertThrows(RecipeOrderException.class, call);
        assertEquals(expected, error.getStatus(), error.getMessage());
    }

    private static RecipeTemplate recipe(long id, long owner, Visibility visibility) {
        RecipeTemplate recipe = new RecipeTemplate();
        recipe.setId(id);
        recipe.setName("Pancakes");
        recipe.setDescription("Fluffy pancakes");
        recipe.setCreatedBy(owner);
        recipe.setVisibility(visibility);
        NutritionInfo nutrition = new NutritionInfo();
        nutrition.setServings(2);
        recipe.setNutrition(nutrition);
        return recipe;
    }

    private static DeliveryAddress address() {
        return DeliveryAddress.builder().recipientName("Ada").line1("221B Baker Street").city("London").postalCode("NW1").build();
    }

    /** A clock tests can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-10T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
