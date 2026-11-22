package com.processVisualisation.virtualKitchen.ai.platform.credit;

import org.springframework.data.mongodb.core.index.Indexed;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A user's current AI credit balance for the active monthly cycle. Kept as
 * its own 1:1 document (keyed directly by the {@code User.id}) rather than
 * fields on {@code auth.model.User}, so high-frequency reserve/release
 * traffic never contends with unrelated user-profile writes. {@link
 * CreditService} is the only writer; every mutation goes through an atomic
 * MongoDB {@code findAndModify} rather than a read-modify-write.
 */
@Data
@Document(collection = "user_ai_credits")
public class UserAiCredit {

    @Id
    private Long userId;

    private int monthlyAllocation;

    /** Credits currently spendable. Never allowed to go negative by {@link CreditService#reserve}. */
    private int availableBalance;

    /** Sum of credits held by in-flight (reserved but not yet consumed/released) requests. */
    private int reservedBalance;

    private LocalDate cycleStart;
    /** Indexed: the nightly reset finds expired cycles by it. */
    @Indexed
    private LocalDate cycleEnd;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
