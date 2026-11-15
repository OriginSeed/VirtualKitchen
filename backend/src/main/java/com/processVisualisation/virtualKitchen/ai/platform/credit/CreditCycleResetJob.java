package com.processVisualisation.virtualKitchen.ai.platform.credit;

import com.processVisualisation.virtualKitchen.common.logging.LogScope;

import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Resets every user's AI credit balance once their cycle has ended. Runs
 * daily rather than precisely at each user's cycle boundary — safe to run on
 * multiple instances and safe to run more than once the same day, since each
 * document's reset is independently atomic and the {@code cycleEnd < today}
 * filter itself makes a same-day rerun find nothing left to reset.
 */
@Component
public class CreditCycleResetJob {

    private static final Logger log = LoggerFactory.getLogger(CreditCycleResetJob.class);

    private final MongoTemplate mongoTemplate;
    private final AiCreditProperties creditProperties;
    private final AiCreditTransactionRepository transactionRepository;

    public CreditCycleResetJob(
            MongoTemplate mongoTemplate,
            AiCreditProperties creditProperties,
            AiCreditTransactionRepository transactionRepository
    ) {
        this.mongoTemplate = mongoTemplate;
        this.creditProperties = creditProperties;
        this.transactionRepository = transactionRepository;
    }

    @Scheduled(cron = "${ai.credits.cycle-reset-cron:0 0 2 * * *}")
    public void resetExpiredCycles() {
        try (LogScope ignored = LogScope.with(MdcKeys.AREA, "system", MdcKeys.ACTION, "scheduled.credit_cycle_reset", MdcKeys.JOB_ID,
                "credit-reset-" + Long.toHexString(System.currentTimeMillis()))) {
            resetCycles();
        }
    }

    private void resetCycles() {
        LocalDate today = LocalDate.now();
        int allocation = creditProperties.getDefaultMonthlyAllocation();
        LocalDate newCycleStart = today.withDayOfMonth(1);
        LocalDate newCycleEnd = newCycleStart.plusMonths(1);

        int resetCount = 0;
        UserAiCredit expired;
        while ((expired = mongoTemplate.findAndModify(
                Query.query(where("cycleEnd").lt(today)),
                new Update()
                        .set("monthlyAllocation", allocation)
                        .set("availableBalance", allocation)
                        .set("reservedBalance", 0)
                        .set("cycleStart", newCycleStart)
                        .set("cycleEnd", newCycleEnd)
                        .set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true),
                UserAiCredit.class)) != null) {

            AiCreditTransaction tx = new AiCreditTransaction();
            tx.setUserId(expired.getUserId());
            tx.setType(CreditTransactionType.MONTHLY_GRANT);
            tx.setAmount(allocation);
            tx.setBalanceAfter(allocation);
            tx.setCreatedAt(LocalDateTime.now());
            transactionRepository.save(tx);
            resetCount++;
        }

        if (resetCount > 0) {
            log.info("event=ai_credit_cycle_reset_completed users={} allocation={}", resetCount, allocation);
        }
    }
}
