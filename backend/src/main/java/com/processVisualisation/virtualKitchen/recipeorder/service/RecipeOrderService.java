package com.processVisualisation.virtualKitchen.recipeorder.service;

import com.processVisualisation.virtualKitchen.auth.model.DeliveryAddress;
import com.processVisualisation.virtualKitchen.auth.model.User;
import com.processVisualisation.virtualKitchen.auth.repository.UserRepository;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.kitchen.model.Kitchen;
import com.processVisualisation.virtualKitchen.kitchen.repository.KitchenRepository;
import com.processVisualisation.virtualKitchen.kitchen.reservation.InventoryReservationStore;
import com.processVisualisation.virtualKitchen.kitchen.reservation.InventoryReservationStore.Outcome;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeThumbnailDTO;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeThumbnailService;
import com.processVisualisation.virtualKitchen.recipe.validation.RecipeReadinessEvaluator;
import com.processVisualisation.virtualKitchen.recipeorder.config.RecipeOrderProperties;
import com.processVisualisation.virtualKitchen.recipeorder.dto.RecipeOrderRequests;
import com.processVisualisation.virtualKitchen.recipeorder.exception.RecipeOrderException;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.Allocation;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.AllocationState;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.OrderEvent;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.PaymentAttempt;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.PaymentStatus;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrder.Pricing;
import com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrderStatus;
import com.processVisualisation.virtualKitchen.recipeorder.repository.RecipeOrderStore;
import com.processVisualisation.virtualKitchen.recipeorder.service.IngredientAvailabilityService.AllocationPlan;
import com.processVisualisation.virtualKitchen.recipeorder.service.IngredientAvailabilityService.AvailabilityReport;
import com.processVisualisation.virtualKitchen.store.model.Ingredient;
import com.processVisualisation.virtualKitchen.store.repository.IngredientRepository;
import com.processVisualisation.virtualKitchen.store.units.UnitConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.processVisualisation.virtualKitchen.recipeorder.model.RecipeOrderStatus.*;

/**
 * Owns the recipe-order lifecycle: draft, recipe confirmation, ingredient check, inventory
 * reservation, demo payment, simulated preparation and delivery, and completion.
 * <p>
 * Every method takes the caller's user id from the authenticated session; an order (or recipe)
 * that isn't the caller's is reported as not found. Every state change is decided here from the
 * order's current status ({@link RecipeOrderLifecycle}) and written with a version check, so
 * concurrent or repeated requests cannot both apply.
 * <p>
 * Inventory rules: a draft or an ingredient check never touches stock. Reserving holds the needed
 * amounts on the kitchen's inventory rows (atomically, so two orders can't hold the same stock); a
 * declined payment or a cancellation releases them; completion consumes them — once. The
 * allocation plan is written to the order before any row is touched, and each row operation is
 * idempotent, so a request that fails part-way can be retried (or recovered) without leaking or
 * double-counting stock.
 */
@Service
public class RecipeOrderService {

    private static final Logger log = LoggerFactory.getLogger(RecipeOrderService.class);

    public static final String DEMO_APPROVE = "DEMO_APPROVE";
    public static final String DEMO_DECLINE = "DEMO_DECLINE";
    private static final Set<String> DEMO_METHODS = Set.of(DEMO_APPROVE, DEMO_DECLINE);
    private static final int MAX_NOTES = 500;
    private static final int MAX_ADDRESS_FIELD = 200;

    private final RecipeOrderStore orders;
    private final InventoryReservationStore inventory;
    private final IngredientAvailabilityService availability;
    private final RecipeTemplateRepository recipeRepository;
    private final ProcessRepository processRepository;
    private final KitchenRepository kitchenRepository;
    private final UserRepository userRepository;
    private final IngredientRepository ingredientRepository;
    private final RecipeThumbnailService thumbnailService;
    private final SequenceGeneratorService sequenceGenerator;
    private final RecipeRequirementCalculator calculator;
    private final RecipeReadinessEvaluator readinessEvaluator;
    private final RecipeOrderProperties properties;
    private final Clock clock;

    @Autowired
    public RecipeOrderService(
            RecipeOrderStore orders,
            InventoryReservationStore inventory,
            IngredientAvailabilityService availability,
            RecipeTemplateRepository recipeRepository,
            ProcessRepository processRepository,
            KitchenRepository kitchenRepository,
            UserRepository userRepository,
            IngredientRepository ingredientRepository,
            RecipeThumbnailService thumbnailService,
            SequenceGeneratorService sequenceGenerator,
            UnitConversionService conversions,
            RecipeOrderProperties properties
    ) {
        this(orders, inventory, availability, recipeRepository, processRepository, kitchenRepository, userRepository,
                ingredientRepository, thumbnailService, sequenceGenerator, conversions, properties, Clock.systemDefaultZone());
    }

    public RecipeOrderService(
            RecipeOrderStore orders,
            InventoryReservationStore inventory,
            IngredientAvailabilityService availability,
            RecipeTemplateRepository recipeRepository,
            ProcessRepository processRepository,
            KitchenRepository kitchenRepository,
            UserRepository userRepository,
            IngredientRepository ingredientRepository,
            RecipeThumbnailService thumbnailService,
            SequenceGeneratorService sequenceGenerator,
            UnitConversionService conversions,
            RecipeOrderProperties properties,
            Clock clock
    ) {
        this.orders = orders;
        this.inventory = inventory;
        this.availability = availability;
        this.recipeRepository = recipeRepository;
        this.processRepository = processRepository;
        this.kitchenRepository = kitchenRepository;
        this.userRepository = userRepository;
        this.ingredientRepository = ingredientRepository;
        this.thumbnailService = thumbnailService;
        this.sequenceGenerator = sequenceGenerator;
        this.calculator = new RecipeRequirementCalculator(conversions);
        this.readinessEvaluator = new RecipeReadinessEvaluator();
        this.properties = properties;
        this.clock = clock;
    }

    // --- reads ---

    public RecipeOrder get(Long userId, Long orderId) {
        return load(userId, orderId);
    }

    public List<RecipeOrder> listMine(Long userId) {
        return orders.findByUserId(userId);
    }

    /** The ingredient comparison for a confirmed order, read now. Never changes stock. */
    public AvailabilityReport availability(Long userId, Long orderId) {
        RecipeOrder order = load(userId, orderId);
        if (order.getStatus() == DRAFT) {
            throw RecipeOrderException.conflict("Confirm the recipe before checking ingredients");
        }
        return availability.check(order);
    }

    // --- draft and confirmation ---

    /**
     * Starts a draft order and takes the snapshot of the recipe the user is about to confirm. Only
     * recipes in the caller's own kitchen can be ordered: another user's private recipe is "not
     * found", and a public one has to be copied into the caller's kitchen first.
     */
    public RecipeOrder createDraft(Long userId, RecipeOrderRequests.Create request) {
        if (request == null || request.recipeId() == null) throw RecipeOrderException.badRequest("recipeId is required");
        int servings = validServings(request.servings() == null ? 1 : request.servings());
        RecipeTemplate recipe = ownedRecipe(userId, request.recipeId());
        Kitchen kitchen = kitchenOf(userId);
        User user = userRepository.findById(userId).orElse(null);

        List<Process> processes = processRepository.findByRecipeId(recipe.getId());
        RecipeOrder.RecipeSnapshot snapshot = snapshot(recipe, processes, user);
        if (snapshot.getStepCount() == 0) {
            throw RecipeOrderException.unprocessable("This recipe has no steps yet, so there is nothing to prepare. Add steps in the recipe editor first.");
        }
        // The kitchen cooks the recipe as structured: its preparations must feed one connected main process.
        // (Uncatalogued ingredients are not blockers here: the order's ingredient check lists them to resolve.)
        RecipeReadinessEvaluator.Readiness readiness = readinessEvaluator.evaluate(processes);
        if (!readiness.ready()) throw RecipeOrderException.unprocessable(RecipeReadinessEvaluator.summarize(readiness));

        LocalDateTime now = now();
        RecipeOrder order = new RecipeOrder();
        order.setId(sequenceGenerator.generateSequence(RecipeOrder.SEQUENCE_NAME));
        order.setOrderCode(String.format("RO-%06d", order.getId()));
        order.setUserId(userId);
        order.setKitchenId(kitchen.getId());
        order.setKitchenName(kitchen.getName());
        order.setRecipeId(recipe.getId());
        order.setStatus(DRAFT);
        order.setRecipeSnapshot(snapshot);
        order.setTotalSteps(snapshot.getStepCount());
        Integer base = recipe.getNutrition() != null && recipe.getNutrition().getServings() != null
                && recipe.getNutrition().getServings() > 0 ? recipe.getNutrition().getServings() : null;
        order.setBaseServings(base);
        setServings(order, servings);
        order.setNotes(cleanNotes(request.notes()));
        DeliveryAddress address = request.deliveryAddress() != null ? cleanAddress(request.deliveryAddress())
                : user != null ? user.getDeliveryAddress() : null;
        order.setDeliveryAddress(address);
        order.setPricing(pricing());
        order.getTimestamps().setCreatedAt(now);
        order.getTimestamps().setUpdatedAt(now);
        event(order, "CREATED", null, DRAFT, "Order draft created", false);
        RecipeOrder created = orders.insert(order);
        log.info("event=recipe_order_created orderId={} recipeId={} kitchenId={} status={} servings={} stepCount={} processCount={}",
                created.getId(), created.getRecipeId(), created.getKitchenId(), created.getStatus(), created.getServings(),
                snapshot.getStepCount(), processes.size());
        return created;
    }

    /** Changes servings, address or notes. A new serving count needs the recipe re-confirmed. */
    public RecipeOrder updateDetails(Long userId, Long orderId, RecipeOrderRequests.Update request) {
        RecipeOrder order = load(userId, orderId);
        RecipeOrderLifecycle.require(RecipeOrderLifecycle.EDITABLE, order.getStatus(), "edit");
        if (request == null) return order;
        RecipeOrderStatus from = order.getStatus();
        if (request.servings() != null && request.servings() != order.getServings()) {
            setServings(order, validServings(request.servings()));
            order.setStatus(DRAFT);
            order.setRequirements(new ArrayList<>());
            order.setRequirementIssues(new ArrayList<>());
        }
        if (request.deliveryAddress() != null) order.setDeliveryAddress(cleanAddress(request.deliveryAddress()));
        if (request.notes() != null) order.setNotes(cleanNotes(request.notes()));
        if (order.getStatus() != from) {
            event(order, "DETAILS_CHANGED", from, order.getStatus(), "Servings changed to " + order.getServings() + "; confirm the recipe again", false);
        }
        RecipeOrder saved = save(order);
        log.info("event=recipe_order_updated orderId={} fromStatus={} status={} servings={} addressChanged={} notesChanged={}",
                saved.getId(), from, saved.getStatus(), saved.getServings(),
                request.deliveryAddress() != null, request.notes() != null);
        return saved;
    }

    /**
     * The user's explicit confirmation of the recipe (as snapshotted) and order details: the
     * ingredient requirements are worked out from the snapshot for the chosen servings.
     */
    public RecipeOrder confirm(Long userId, Long orderId, RecipeOrderRequests.Confirm request) {
        RecipeOrder order = load(userId, orderId);
        RecipeOrderLifecycle.require(RecipeOrderLifecycle.CONFIRMABLE, order.getStatus(), "confirm");
        validServings(order.getServings());
        requireCompleteAddress(order.getDeliveryAddress());
        // The recipe must still be the caller's to order.
        ownedRecipe(userId, order.getRecipeId());

        List<Process> processes = order.getRecipeSnapshot().getProcesses();
        Map<Long, Ingredient> ingredients = new HashMap<>();
        ingredientRepository.findAllById(RecipeRequirementCalculator.referencedIngredientIds(processes))
                .forEach(ingredient -> ingredients.put(ingredient.getId(), ingredient));
        RecipeRequirementCalculator.Result result = calculator.calculate(processes, order.getScale(), ingredients);
        order.setRequirements(new ArrayList<>(result.requirements()));
        order.setRequirementIssues(new ArrayList<>(result.issues()));
        order.setTotalSteps(result.stepCount());

        if (request != null && request.saveAddressAsDefault()) {
            userRepository.findById(userId).ifPresent(user -> {
                user.setDeliveryAddress(order.getDeliveryAddress());
                userRepository.save(user);
            });
        }

        order.setStatus(AWAITING_INGREDIENTS);
        order.getTimestamps().setRecipeConfirmedAt(now());
        event(order, "RECIPE_CONFIRMED", DRAFT, AWAITING_INGREDIENTS,
                "Recipe confirmed for " + order.getServings() + (order.getBaseServings() != null ? " servings" : " batch(es)"), false);
        RecipeOrder saved = save(order);
        log.info("event=recipe_order_confirmed orderId={} status={} servings={} requirementCount={} issueCount={} stepCount={}",
                saved.getId(), saved.getStatus(), saved.getServings(), saved.getRequirements().size(),
                saved.getRequirementIssues().size(), saved.getTotalSteps());
        return saved;
    }

    // --- inventory reservation ---

    /**
     * Holds the order's ingredients on the kitchen's inventory and moves it to payment. If stock is
     * short now, nothing is held and the order stays where it is (409). If stock runs short while
     * reserving — another order took it — everything held so far is released and the order becomes
     * INVENTORY_CONFLICT.
     */
    public RecipeOrder reserve(Long userId, Long orderId) {
        RecipeOrder order = load(userId, orderId);
        if (order.getStatus() == RESERVING) {
            if (!isStale(order)) throw RecipeOrderException.conflict("A reservation for this order is already in progress");
            order = recoverInterruptedReservation(order);
        }
        RecipeOrderLifecycle.require(RecipeOrderLifecycle.RESERVABLE, order.getStatus(), "reserve ingredients for");
        if (!order.getRequirementIssues().isEmpty()) {
            throw RecipeOrderException.unprocessable("Some ingredients of this recipe can't be checked against your inventory: "
                    + order.getRequirementIssues().get(0).getMessage());
        }
        // Anything an earlier attempt still holds (e.g. a payment declined mid-release) goes back first.
        if (!order.getAllocations().isEmpty()) {
            releaseAll(order);
            order = save(order);
        }

        int attempt = order.getReservationAttempt() + 1;
        AllocationPlan plan = availability.plan(order, attempt);
        if (!plan.feasible()) {
            log.warn("event=recipe_order_reserve_rejected orderId={} status={} reason=insufficient_stock shortageCount={}",
                    order.getId(), order.getStatus(), plan.shortages().size());
            throw RecipeOrderException.conflict("Not enough ingredients in your kitchen: " + plan.shortages().stream()
                    .map(line -> line.name() + " (missing " + format(line.missing()) + " " + line.unit() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }

        // Write-ahead: the plan is on the order before any inventory row changes.
        RecipeOrderStatus from = order.getStatus();
        order.setStatus(RESERVING);
        order.setReservationAttempt(attempt);
        order.setReservingStartedAt(now());
        order.setAllocations(new ArrayList<>(plan.allocations()));
        order = save(order);

        boolean failed = false;
        for (Allocation allocation : order.getAllocations()) {
            Outcome outcome = inventory.reserve(allocation.getInventoryId(), allocation.getQuantity(), allocation.getReserveKey());
            if (outcome == Outcome.APPLIED || outcome == Outcome.ALREADY_APPLIED) {
                allocation.setState(AllocationState.RESERVED);
            } else {
                allocation.setState(AllocationState.FAILED);
                allocation.setNote("Could not reserve: " + outcome);
                failed = true;
                break;
            }
        }

        order.setReservingStartedAt(null);
        if (failed) {
            releaseAll(order);
            order.setStatus(INVENTORY_CONFLICT);
            event(order, "INVENTORY_CONFLICT", from, INVENTORY_CONFLICT,
                    "Stock changed while reserving (another order may have used it). Nothing is held; check the ingredients again.", false);
        } else {
            order.setStatus(AWAITING_PAYMENT);
            order.getTimestamps().setReservedAt(now());
            event(order, "INGREDIENTS_RESERVED", from, AWAITING_PAYMENT,
                    "Reserved " + order.getAllocations().size() + " inventory item(s) for this order", false);
        }
        RecipeOrder saved = save(order);
        if (failed) {
            log.warn("event=recipe_order_reserve_failed orderId={} status={} reason=inventory_conflict attempt={} releasedCount={}",
                    saved.getId(), saved.getStatus(), attempt, saved.getReleasedAllocations().size());
        } else {
            log.info("event=recipe_order_reserved orderId={} status={} attempt={} allocationCount={}",
                    saved.getId(), saved.getStatus(), attempt, saved.getAllocations().size());
        }
        return saved;
    }

    // --- demo payment ---

    /**
     * Records a simulated payment for an order awaiting payment. The outcome is the demo option the
     * user picked; the amount is the server's. Repeating a request with the same idempotency key
     * returns the order as that request left it.
     */
    public RecipeOrder pay(Long userId, Long orderId, RecipeOrderRequests.Pay request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw RecipeOrderException.badRequest("idempotencyKey is required");
        }
        String method = request.method() == null ? "" : request.method().trim().toUpperCase(Locale.ROOT);
        if (!DEMO_METHODS.contains(method)) {
            throw RecipeOrderException.badRequest("method must be one of " + DEMO_METHODS + " (demo payments only)");
        }
        if (!properties.isDemoPaymentEnabled()) {
            throw RecipeOrderException.unprocessable("Demo payments are disabled");
        }

        RecipeOrder order = load(userId, orderId);
        if (findAttempt(order, request.idempotencyKey()) != null) return order;
        if (order.getStatus().isPaid()) throw RecipeOrderException.conflict("This order has already been paid");
        RecipeOrderLifecycle.require(Set.of(AWAITING_PAYMENT), order.getStatus(), "pay for");

        LocalDateTime now = now();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setIdempotencyKey(request.idempotencyKey());
        attempt.setMethod(method);
        attempt.setAmount(order.getPricing().getTotal());
        attempt.setCurrency(order.getPricing().getCurrency());
        attempt.setProcessedAt(now);
        attempt.setReference("DEMO-PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        order.getPaymentAttempts().add(attempt);
        order.setPayment(attempt);

        if (DEMO_APPROVE.equals(method)) {
            attempt.setStatus(PaymentStatus.PAID);
            order.setStatus(CONFIRMED);
            order.getTimestamps().setPaidAt(now);
            event(order, "PAYMENT_SUCCEEDED", AWAITING_PAYMENT, CONFIRMED,
                    "Demo payment approved (simulated: no money was moved)", true);
            RecipeOrder paid = savePayment(order, userId, request.idempotencyKey());
            log.info("event=recipe_order_paid orderId={} status={} paymentAttemptCount={} demo=true",
                    paid.getId(), paid.getStatus(), paid.getPaymentAttempts().size());
            return paid;
        }

        attempt.setStatus(PaymentStatus.FAILED);
        attempt.setFailureReason("Declined by the demo payment option");
        order.setStatus(PAYMENT_FAILED);
        order.getTimestamps().setPaymentFailedAt(now);
        event(order, "PAYMENT_FAILED", AWAITING_PAYMENT, PAYMENT_FAILED,
                "Demo payment declined; the reserved ingredients were released", true);
        order = savePayment(order, userId, request.idempotencyKey());
        // Release after the failure is recorded; if this is interrupted, the next reserve or cancel finishes it.
        releaseAll(order);
        RecipeOrder declined = save(order);
        log.warn("event=recipe_order_payment_declined orderId={} status={} reason=demo_decline paymentAttemptCount={}",
                declined.getId(), declined.getStatus(), declined.getPaymentAttempts().size());
        return declined;
    }

    // --- simulated preparation and delivery ---

    /**
     * Moves a paid order one simulated stage on: each recipe step, then quality check, out for
     * delivery and delivered. Reaching delivered finalises ingredient consumption exactly once.
     */
    public RecipeOrder advance(Long userId, Long orderId, RecipeOrderRequests.Advance request) {
        RecipeOrder order = load(userId, orderId);
        if (order.getStatus() == COMPLETING) return finishCompletion(order);
        RecipeOrderLifecycle.require(RecipeOrderLifecycle.ADVANCEABLE, order.getStatus(), "advance");
        if (request != null && request.expectedStatus() != null) {
            boolean sameStage = request.expectedStatus() == order.getStatus()
                    && (order.getStatus() != PREPARING || Objects.equals(request.expectedStepIndex(), order.getCurrentStepIndex()));
            if (!sameStage) throw RecipeOrderException.conflict("This order has already moved on; refresh to see its current stage");
        }

        RecipeOrderStatus from = order.getStatus();
        RecipeOrderLifecycle.Next next = RecipeOrderLifecycle.next(from, order.getCurrentStepIndex(), order.getTotalSteps());
        LocalDateTime now = now();
        switch (next.status()) {
            case PREPARING -> {
                if (from == CONFIRMED) order.getTimestamps().setPreparationStartedAt(now);
                order.setStatus(PREPARING);
                order.setCurrentStepIndex(next.stepIndex());
                event(order, from == CONFIRMED ? "PREPARATION_STARTED" : "STEP_STARTED", from, PREPARING, next.stepIndex(),
                        "Simulated preparation: step " + (next.stepIndex() + 1) + " of " + order.getTotalSteps(), true);
            }
            case QUALITY_CHECK -> {
                order.setStatus(QUALITY_CHECK);
                order.setCurrentStepIndex(null);
                order.getTimestamps().setQualityCheckAt(now);
                event(order, "QUALITY_CHECK", from, QUALITY_CHECK, "Simulated quality check", true);
            }
            case OUT_FOR_DELIVERY -> {
                order.setStatus(OUT_FOR_DELIVERY);
                order.getTimestamps().setOutForDeliveryAt(now);
                event(order, "OUT_FOR_DELIVERY", from, OUT_FOR_DELIVERY, "Simulated delivery: out for delivery", true);
            }
            case COMPLETED -> {
                order.setStatus(COMPLETING);
                order.getTimestamps().setDeliveredAt(now);
                event(order, "DELIVERED", from, COMPLETING, "Simulated delivery: delivered", true);
                RecipeOrder delivered = save(order);
                logAdvanced(delivered, from);
                return finishCompletion(delivered);
            }
            default -> throw RecipeOrderException.conflict("Unexpected next stage " + next.status());
        }
        RecipeOrder saved = save(order);
        logAdvanced(saved, from);
        return saved;
    }

    private static void logAdvanced(RecipeOrder order, RecipeOrderStatus from) {
        log.info("event=recipe_order_advanced orderId={} fromStatus={} status={} stepIndex={} totalSteps={}",
                order.getId(), from, order.getStatus(), order.getCurrentStepIndex(), order.getTotalSteps());
    }

    /** Consumes every held ingredient (each row operation applies at most once) and completes the order. */
    private RecipeOrder finishCompletion(RecipeOrder order) {
        for (Allocation allocation : order.getAllocations()) {
            if (allocation.getState() == AllocationState.CONSUMED) continue;
            Outcome outcome = inventory.consume(allocation.getInventoryId(), allocation.getQuantity(),
                    allocation.getReserveKey(), allocation.getReleaseKey(), allocation.getConsumeKey());
            if (outcome == Outcome.APPLIED || outcome == Outcome.ALREADY_APPLIED) {
                allocation.setState(AllocationState.CONSUMED);
                allocation.setNote(null);
            } else {
                allocation.setState(AllocationState.FAILED);
                allocation.setNote("Not consumed: " + outcome);
                log.warn("event=recipe_order_consume_failed orderId={} inventoryId={} outcome={}",
                        order.getId(), allocation.getInventoryId(), outcome);
            }
        }
        order.setStatus(COMPLETED);
        order.getTimestamps().setCompletedAt(now());
        event(order, "COMPLETED", COMPLETING, COMPLETED, "Order complete; ingredient usage recorded", false);
        RecipeOrder completed = save(order);
        log.info("event=recipe_order_completed orderId={} status={} allocationCount={}",
                completed.getId(), completed.getStatus(), completed.getAllocations().size());
        return completed;
    }

    // --- cancellation ---

    /** Cancels an order that hasn't started preparation, releasing anything held for it. */
    public RecipeOrder cancel(Long userId, Long orderId) {
        RecipeOrder order = load(userId, orderId);
        if (order.getStatus() == CANCELLED) return order;
        if (order.getStatus() == RESERVING) {
            if (!isStale(order)) throw RecipeOrderException.conflict("A reservation for this order is in progress; try again in a moment");
            order = recoverInterruptedReservation(order);
        }
        if (!RecipeOrderLifecycle.CANCELLABLE.contains(order.getStatus())) {
            throw RecipeOrderException.conflict(order.getStatus().isPaid()
                    ? "Preparation has started, so this order can no longer be cancelled"
                    : "Cannot cancel an order that is " + RecipeOrderLifecycle.label(order.getStatus()));
        }
        RecipeOrderStatus from = order.getStatus();
        releaseAll(order);
        boolean refunded = false;
        if (order.getPayment() != null && order.getPayment().getStatus() == PaymentStatus.PAID) {
            order.getPayment().setStatus(PaymentStatus.REFUNDED);
            event(order, "PAYMENT_REFUNDED", from, CANCELLED, "Demo payment refunded (simulated)", true);
            refunded = true;
        }
        order.setStatus(CANCELLED);
        order.getTimestamps().setCancelledAt(now());
        event(order, "CANCELLED", from, CANCELLED, "Order cancelled; reserved ingredients released", false);
        RecipeOrder cancelled = save(order);
        log.info("event=recipe_order_cancelled orderId={} fromStatus={} status={} refunded={} releasedCount={}",
                cancelled.getId(), from, cancelled.getStatus(), refunded, cancelled.getReleasedAllocations().size());
        return cancelled;
    }

    // --- helpers ---

    private RecipeOrder recoverInterruptedReservation(RecipeOrder order) {
        log.warn("event=recipe_order_reservation_recovered orderId={}", order.getId());
        releaseAll(order);
        order.setReservingStartedAt(null);
        order.setStatus(INVENTORY_CONFLICT);
        event(order, "RESERVATION_RECOVERED", RESERVING, INVENTORY_CONFLICT, "An interrupted reservation was released", false);
        return save(order);
    }

    /** Releases every allocation of the current attempt (a no-op for any never applied) and archives them. */
    private void releaseAll(RecipeOrder order) {
        for (Allocation allocation : order.getAllocations()) {
            if (allocation.getState() == AllocationState.CONSUMED) continue;
            Outcome outcome = inventory.release(allocation.getInventoryId(), allocation.getQuantity(),
                    allocation.getReserveKey(), allocation.getReleaseKey(), allocation.getConsumeKey());
            if (outcome == Outcome.APPLIED || outcome == Outcome.ALREADY_APPLIED) {
                allocation.setState(AllocationState.RELEASED);
            } else if (outcome == Outcome.NOT_RESERVED) {
                allocation.setState(AllocationState.RELEASED);
                allocation.setNote("Never held; nothing to release");
            } else {
                allocation.setNote("Release: " + outcome);
            }
        }
        order.getReleasedAllocations().addAll(order.getAllocations());
        order.setAllocations(new ArrayList<>());
    }

    private RecipeOrder savePayment(RecipeOrder order, Long userId, String idempotencyKey) {
        if (orders.replaceIfVersion(order)) return order;
        // Lost a race: if a concurrent request with the same key already recorded this payment, answer with it.
        RecipeOrder current = load(userId, order.getId());
        if (findAttempt(current, idempotencyKey) != null) return current;
        throw RecipeOrderException.conflict("This order was updated by another request; refresh and try again");
    }

    private RecipeOrder save(RecipeOrder order) {
        order.getTimestamps().setUpdatedAt(now());
        if (!orders.replaceIfVersion(order)) {
            throw RecipeOrderException.conflict("This order was updated by another request; refresh and try again");
        }
        return order;
    }

    private RecipeOrder load(Long userId, Long orderId) {
        if (userId == null || orderId == null) throw RecipeOrderException.notFound();
        return orders.findById(orderId)
                .filter(order -> userId.equals(order.getUserId()))
                .orElseThrow(RecipeOrderException::notFound);
    }

    private RecipeTemplate ownedRecipe(Long userId, Long recipeId) {
        RecipeTemplate recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new RecipeOrderException("Recipe not found", org.springframework.http.HttpStatus.NOT_FOUND));
        if (!userId.equals(recipe.getCreatedBy())) {
            if (recipe.getVisibility() == Visibility.PUBLIC) {
                throw RecipeOrderException.unprocessable("This is another user's public recipe. Copy it to your kitchen to order it.");
            }
            throw new RecipeOrderException("Recipe not found", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return recipe;
    }

    private Kitchen kitchenOf(Long userId) {
        List<Kitchen> kitchens = kitchenRepository.findByOwnerId(userId);
        if (kitchens == null || kitchens.isEmpty()) {
            throw RecipeOrderException.unprocessable("You don't have a kitchen yet");
        }
        return kitchens.get(0);
    }

    private RecipeOrder.RecipeSnapshot snapshot(RecipeTemplate recipe, List<Process> processes, User owner) {
        RecipeOrder.RecipeSnapshot snapshot = new RecipeOrder.RecipeSnapshot();
        snapshot.setRecipeId(recipe.getId());
        snapshot.setName(recipe.getName());
        snapshot.setDescription(recipe.getDescription());
        snapshot.setOwnerId(recipe.getCreatedBy());
        snapshot.setOwnerName(owner != null ? owner.getName() : null);
        snapshot.setNutrition(recipe.getNutrition());
        snapshot.setProcesses(ProcessSnapshots.copyAll(processes));
        snapshot.setStepCount(calculator.calculate(snapshot.getProcesses(), 1, Map.of()).stepCount());
        snapshot.setCapturedAt(now());
        snapshot.setFallbackIcon(RecipeThumbnailService.DEFAULT_RECIPE_ICON);
        try {
            RecipeThumbnailDTO thumbnail = thumbnailService.resolveAll(List.of(recipe.getId())).get(recipe.getId());
            if (thumbnail != null) {
                snapshot.setThumbnailUrl(thumbnail.getThumbnailUrl());
                if (thumbnail.getFallbackIcon() != null) snapshot.setFallbackIcon(thumbnail.getFallbackIcon());
            }
        } catch (RuntimeException e) {
            log.warn("event=recipe_order_thumbnail_failed recipeId={} errorType={}", recipe.getId(), e.getClass().getSimpleName());
        }
        return snapshot;
    }

    private Pricing pricing() {
        Pricing pricing = new Pricing();
        pricing.setIngredientsCost(0); // the ingredients come from the user's own kitchen inventory
        pricing.setPreparationFee(properties.getPreparationFee());
        pricing.setDeliveryFee(properties.getDeliveryFee());
        pricing.setTotal(Math.round((properties.getPreparationFee() + properties.getDeliveryFee()) * 100) / 100.0);
        pricing.setCurrency(properties.getCurrency());
        pricing.setDemo(true);
        return pricing;
    }

    private void setServings(RecipeOrder order, int servings) {
        order.setServings(servings);
        order.setScale(order.getBaseServings() != null ? (double) servings / order.getBaseServings() : servings);
    }

    private int validServings(int servings) {
        if (servings < 1 || servings > properties.getMaxServings()) {
            throw RecipeOrderException.badRequest("Servings must be between 1 and " + properties.getMaxServings());
        }
        return servings;
    }

    private static String cleanNotes(String notes) {
        if (notes == null) return null;
        String trimmed = notes.trim();
        if (trimmed.length() > MAX_NOTES) throw RecipeOrderException.badRequest("Notes must be at most " + MAX_NOTES + " characters");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static DeliveryAddress cleanAddress(DeliveryAddress address) {
        return DeliveryAddress.builder()
                .recipientName(cleanField(address.getRecipientName(), "recipient name"))
                .line1(cleanField(address.getLine1(), "address line 1"))
                .line2(cleanField(address.getLine2(), "address line 2"))
                .city(cleanField(address.getCity(), "city"))
                .state(cleanField(address.getState(), "state"))
                .postalCode(cleanField(address.getPostalCode(), "postal code"))
                .phone(cleanField(address.getPhone(), "phone"))
                .build();
    }

    private static String cleanField(String value, String label) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > MAX_ADDRESS_FIELD) throw RecipeOrderException.badRequest("The " + label + " is too long");
        return trimmed.isEmpty() ? null : trimmed;
    }

    static void requireCompleteAddress(DeliveryAddress address) {
        if (address == null || blank(address.getRecipientName()) || blank(address.getLine1())
                || blank(address.getCity()) || blank(address.getPostalCode())) {
            throw RecipeOrderException.badRequest("A delivery address with recipient name, address line 1, city and postal code is required");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isStale(RecipeOrder order) {
        LocalDateTime started = order.getReservingStartedAt();
        return started == null || Duration.between(started, now()).getSeconds() >= properties.getStaleReservationSeconds();
    }

    private static PaymentAttempt findAttempt(RecipeOrder order, String idempotencyKey) {
        return order.getPaymentAttempts().stream()
                .filter(attempt -> idempotencyKey.equals(attempt.getIdempotencyKey()))
                .findFirst().orElse(null);
    }

    private void event(RecipeOrder order, String type, RecipeOrderStatus from, RecipeOrderStatus to, String message, boolean simulated) {
        event(order, type, from, to, null, message, simulated);
    }

    private void event(RecipeOrder order, String type, RecipeOrderStatus from, RecipeOrderStatus to, Integer stepIndex,
                       String message, boolean simulated) {
        OrderEvent event = new OrderEvent();
        event.setType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setStepIndex(stepIndex);
        event.setMessage(message);
        event.setSimulated(simulated);
        event.setAt(now());
        order.getEvents().add(event);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 100) / 100.0);
    }
}
