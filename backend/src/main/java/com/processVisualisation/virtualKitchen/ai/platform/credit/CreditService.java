package com.processVisualisation.virtualKitchen.ai.platform.credit;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Owns every mutation of a user's AI credit balance. Every mutation is a
 * single atomic MongoDB {@code findAndModify} — the same technique already
 * used by {@code common.SequenceGeneratorService} — rather than a
 * read-then-write, so concurrent callers can never together push a user's
 * balance negative: the balance check ({@code availableBalance >= cost}) and
 * the decrement happen as one indivisible document operation.
 * <p>
 * The balance mutation and its audit-ledger entry are two separate writes.
 * If the ledger write fails after the balance write succeeds, {@link
 * UserAiCredit} — the fast-path source of truth for "can this user spend
 * right now" — is still correct; only the audit trail is missing an entry.
 * This is an accepted trade-off at this application's scale rather than
 * requiring multi-document Mongo transactions (which need a replica-set
 * deployment) — see the design doc's scalability-path section.
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final MongoTemplate mongoTemplate;
    private final AiCreditProperties creditProperties;
    private final AiCreditTransactionRepository transactionRepository;

    public CreditService(
            MongoTemplate mongoTemplate,
            AiCreditProperties creditProperties,
            AiCreditTransactionRepository transactionRepository
    ) {
        this.mongoTemplate = mongoTemplate;
        this.creditProperties = creditProperties;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Attempts to atomically reserve {@code cost} credits for {@code userId}.
     * A zero-cost reservation (open-source models) always succeeds without
     * touching the ledger or balance at all.
     *
     * @return the reservation if the user had enough available balance, or
     *         empty if they did not (the caller should fall back to another model)
     */
    public Optional<CreditReservation> reserve(Long userId, int cost, AiCapability capability, String modelKey) {
        if (cost <= 0) {
            return Optional.of(new CreditReservation(null, 0, capability, modelKey));
        }

        ensureAccount(userId);

        Query query = Query.query(where("_id").is(userId).and("availableBalance").gte(cost));
        Update update = new Update().inc("availableBalance", -cost).inc("reservedBalance", cost);

        UserAiCredit updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), UserAiCredit.class);

        if (updated == null) {
            return Optional.empty();
        }

        String txId = appendLedger(userId, CreditTransactionType.RESERVE, -cost, updated.getAvailableBalance(),
                capability, modelKey, null, null);
        return Optional.of(new CreditReservation(txId, cost, capability, modelKey));
    }

    /**
     * Finalizes a reservation as a permanent charge once the request completed successfully.
     *
     * @param usage the request's provider token usage and estimated cost, recorded on the ledger entry (may be null)
     */
    public void consume(Long userId, CreditReservation reservation, String requestId, AiUsageSummary usage) {
        if (reservation == null || reservation.cost() <= 0) {
            return;
        }
        mongoTemplate.updateFirst(
                Query.query(where("_id").is(userId)),
                new Update().inc("reservedBalance", -reservation.cost()),
                UserAiCredit.class);
        appendLedger(userId, CreditTransactionType.CONSUME, 0, currentBalance(userId),
                reservation.capability(), reservation.modelKey(), requestId, usage);
    }

    /**
     * Refunds a reservation when the request failed, timed out, or was cancelled before finishing.
     *
     * @param usage tokens the failed request still spent with the provider, recorded on the ledger entry (may be null)
     */
    public void release(Long userId, CreditReservation reservation, String requestId, AiUsageSummary usage) {
        if (reservation == null || reservation.cost() <= 0) {
            return;
        }
        UserAiCredit updated = mongoTemplate.findAndModify(
                Query.query(where("_id").is(userId)),
                new Update().inc("availableBalance", reservation.cost()).inc("reservedBalance", -reservation.cost()),
                FindAndModifyOptions.options().returnNew(true),
                UserAiCredit.class);
        int balanceAfter = updated != null ? updated.getAvailableBalance() : currentBalance(userId);
        appendLedger(userId, CreditTransactionType.RELEASE, reservation.cost(), balanceAfter,
                reservation.capability(), reservation.modelKey(), requestId, usage);
    }

    /** Reads the current balance, lazily provisioning a default account if none exists yet. */
    public UserAiCredit getOrCreateAccount(Long userId) {
        return ensureAccount(userId);
    }

    public java.util.List<AiCreditTransaction> getHistory(Long userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Admin-only manual balance adjustment (grant or deduction), applied atomically and always
     * ledgered as {@link CreditTransactionType#ADMIN_ADJUSTMENT} — never a raw balance write.
     *
     * @param amount signed amount to add (positive) or deduct (negative)
     * @return the updated account, or empty if a deduction would have pushed the balance negative
     */
    public Optional<UserAiCredit> adjust(Long userId, int amount) {
        ensureAccount(userId);
        int minRequiredBalance = amount < 0 ? -amount : 0;

        Query query = Query.query(where("_id").is(userId).and("availableBalance").gte(minRequiredBalance));
        Update update = new Update().inc("availableBalance", amount);

        UserAiCredit updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), UserAiCredit.class);

        if (updated == null) {
            log.warn("event=ai_credits_grant_rejected targetUserId={} amount={} reason=insufficient_balance", userId, amount);
            return Optional.empty();
        }
        appendLedger(userId, CreditTransactionType.ADMIN_ADJUSTMENT, amount, updated.getAvailableBalance(), null, null, null, null);
        log.info("event=ai_credits_granted targetUserId={} amount={} availableBalance={}",
                userId, amount, updated.getAvailableBalance());
        return Optional.of(updated);
    }

    /**
     * Ensures a {@link UserAiCredit} document exists for {@code userId}, creating one with the
     * configured default allocation if absent. Implemented as a single atomic upsert
     * ({@code $setOnInsert} only) so concurrent first-time callers never create two documents or
     * race each other — the second caller's upsert simply becomes a no-op update matched by
     * {@code _id}.
     */
    private UserAiCredit ensureAccount(Long userId) {
        LocalDate cycleStart = LocalDate.now().withDayOfMonth(1);
        LocalDate cycleEnd = cycleStart.plusMonths(1);
        int allocation = creditProperties.getDefaultMonthlyAllocation();

        Query query = Query.query(where("_id").is(userId));
        Update update = new Update()
                .setOnInsert("_id", userId)
                .setOnInsert("monthlyAllocation", allocation)
                .setOnInsert("availableBalance", allocation)
                .setOnInsert("reservedBalance", 0)
                .setOnInsert("cycleStart", cycleStart)
                .setOnInsert("cycleEnd", cycleEnd)
                .setOnInsert("createdAt", LocalDateTime.now())
                .setOnInsert("updatedAt", LocalDateTime.now());

        return mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                UserAiCredit.class);
    }

    private int currentBalance(Long userId) {
        UserAiCredit current = mongoTemplate.findOne(Query.query(where("_id").is(userId)), UserAiCredit.class);
        return current != null ? current.getAvailableBalance() : 0;
    }

    private String appendLedger(Long userId, CreditTransactionType type, int amount, int balanceAfter,
                                 AiCapability capability, String modelKey, String requestId, AiUsageSummary usage) {
        AiCreditTransaction tx = new AiCreditTransaction();
        tx.setUserId(userId);
        tx.setRequestId(requestId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(balanceAfter);
        tx.setCapability(capability);
        tx.setModelKey(modelKey);
        tx.setUsage(usage);
        tx.setEstimatedCostUsd(usage != null ? usage.estimatedCostUsd() : null);
        tx.setCreatedAt(LocalDateTime.now());
        return transactionRepository.save(tx).getId();
    }
}
