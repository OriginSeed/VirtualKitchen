package com.processVisualisation.virtualKitchen.auth.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Persistent entity representing a single one-time passcode (OTP) issued for
 * a specific email address and purpose, stored in the "otps" MongoDB
 * collection. Stores only the hashed code (never the plaintext value),
 * together with its expiry, verification/consumption state, and the number
 * of failed verification attempts made against it. Populated and read by
 * OtpRepository, and managed through its full lifecycle by OtpService.
 */
@Data
@Document(collection = "otps")
public class Otp {

    @Id
    private String id;

    @Indexed
    private String email;

    private OtpPurpose purpose;

    private String codeHash;

    private LocalDateTime expiresAt;

    private boolean verified = false;

    private boolean consumed = false;

    private int attempts = 0;

    /**
     * Set by OtpService when the code is created (Mongo auditing is not enabled, so {@code @CreatedDate} alone
     * would leave it null). Codes are deleted by Mongo a day after creation; they expire for use much sooner.
     */
    @CreatedDate
    @Indexed(name = "otp_created_ttl", expireAfter = "1d")
    private LocalDateTime createdAt;
}
