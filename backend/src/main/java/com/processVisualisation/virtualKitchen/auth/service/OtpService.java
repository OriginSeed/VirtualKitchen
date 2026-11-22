package com.processVisualisation.virtualKitchen.auth.service;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.model.Otp;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import com.processVisualisation.virtualKitchen.auth.repository.OtpRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Generates six-digit numeric codes,
 * stores only their hash (never the plaintext code) via a PasswordEncoder,
 * and persists them through OtpRepository. Enforces three security controls
 * configured via app.otp.* properties: an expiration window
 * (app.otp.expiration-minutes) after which a code can no longer be verified,
 * a maximum number of incorrect verification attempts per code
 * (app.otp.max-attempts) before it is locked out, and a resend rate limit
 * capping how many codes may be generated per email+purpose within a rolling
 * window (app.otp.resend-window-minutes, app.otp.max-per-window).
 * <p>
 * Every state change on a code is a single conditional database update, so concurrent requests can't get past
 * the attempt limit together, and a code can be used (verified or consumed) only once even when two requests
 * submit it at the same moment.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository otpRepository;
    private final MongoTemplate mongoTemplate;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final OtpProperties limits;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OtpService(OtpRepository otpRepository, MongoTemplate mongoTemplate, EmailService emailService,
                      PasswordEncoder passwordEncoder, OtpProperties limits) {
        this(otpRepository, mongoTemplate, emailService, passwordEncoder, limits, Clock.systemDefaultZone());
    }

    OtpService(OtpRepository otpRepository, MongoTemplate mongoTemplate, EmailService emailService,
               PasswordEncoder passwordEncoder, OtpProperties limits, Clock clock) {
        this.otpRepository = otpRepository;
        this.mongoTemplate = mongoTemplate;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Generates a new six-digit OTP for the given email and purpose, persists
     * its hash with an expiry of app.otp.expiration-minutes minutes from now,
     * and emails the plaintext code to the user. Enforces the resend rate
     * limit before generating a new code.
     *
     * @param email the destination email address the OTP is generated for
     * @param purpose the reason the OTP is being generated (email verification, login, or password reset)
     * @throws AuthException if too many codes have already been requested for this email and purpose within the resend window
     */
    public void generateAndSendOtp(String email, OtpPurpose purpose) {
        LocalDateTime now = LocalDateTime.now(clock);
        long recentCount = otpRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, now.minusMinutes(limits.getResendWindowMinutes()));

        if (recentCount >= limits.getMaxPerWindow()) {
            throw new AuthException(
                    "Too many verification codes requested. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        Otp otp = new Otp();
        otp.setEmail(email);
        otp.setPurpose(purpose);
        otp.setCodeHash(passwordEncoder.encode(code));
        otp.setExpiresAt(now.plusMinutes(limits.getExpirationMinutes()));
        // Set explicitly: Mongo auditing is not enabled, so @CreatedDate alone would leave it null, and both the
        // resend window and "latest code" lookups depend on it.
        otp.setCreatedAt(now);

        otpRepository.save(otp);

        emailService.sendOtpEmail(email, code, purpose);
    }

    /**
     * Validates the given OTP code and, if correct, marks it both verified
     * and consumed in a single step. Used by flows (email verification, OTP
     * login) that need no separate confirmation step after verification.
     *
     * @param email the email address the OTP was issued for
     * @param code the plaintext OTP code submitted by the caller
     * @param purpose the purpose the OTP must have been issued for
     * @throws AuthException if no active OTP is found, it is already used, has expired, has exceeded its attempt limit, or the code does not match
     */
    public void verifyAndConsume(String email, String code, OtpPurpose purpose) {
        Otp otp = validateAndGetActiveOtp(email, code, purpose);
        markUsed(otp, new Update().set("verified", true).set("consumed", true));
    }

    /**
     * Validates the given OTP code and, if correct, marks it verified without
     * consuming it. Used to gate the password-reset flow: the code stays
     * usable as proof of verification until consumeVerified is later called
     * to finalize the reset.
     *
     * @param email the email address the OTP was issued for
     * @param code the plaintext OTP code submitted by the caller
     * @param purpose the purpose the OTP must have been issued for
     * @throws AuthException if no active OTP is found, it is already used, has expired, has exceeded its attempt limit, or the code does not match
     */
    public void verifyOnly(String email, String code, OtpPurpose purpose) {
        Otp otp = validateAndGetActiveOtp(email, code, purpose);
        markUsed(otp, new Update().set("verified", true));
    }

    /**
     * Confirms that the most recent OTP for the given email and purpose has
     * already been verified (via verifyOnly) and not yet consumed, then marks
     * it consumed. Used as the final step of the password-reset flow after
     * verifyOnly has confirmed the code.
     *
     * @param email the email address the OTP was issued for
     * @param purpose the purpose the OTP must have been issued for
     * @throws AuthException if no verified, unconsumed OTP exists for this email and purpose, or it has since expired
     */
    public void consumeVerified(String email, OtpPurpose purpose) {
        Otp otp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> new AuthException("Please verify your code first", HttpStatus.BAD_REQUEST));

        if (!otp.isVerified() || otp.isConsumed()) {
            throw new AuthException("Please verify your code first", HttpStatus.BAD_REQUEST);
        }

        if (otp.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new AuthException("Verification has expired. Please request a new code.", HttpStatus.BAD_REQUEST);
        }

        // Only one concurrent reset may consume the verification.
        Query unconsumed = Query.query(Criteria.where("_id").is(otp.getId())
                .and("verified").is(true).and("consumed").is(false));
        if (mongoTemplate.updateFirst(unconsumed, new Update().set("consumed", true), Otp.class).getModifiedCount() == 0) {
            throw new AuthException("Please verify your code first", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Fetches the most recent OTP for the given email and purpose, checks it
     * is unconsumed/unverified and unexpired, atomically counts this attempt (refusing once the limit is reached),
     * and verifies the submitted code against the stored hash.
     *
     * @param email the email address the OTP was issued for
     * @param code the plaintext OTP code submitted by the caller
     * @param purpose the purpose the OTP must have been issued for
     * @return the validated OTP entity
     * @throws AuthException if no active OTP is found, it is already used, has expired, has exceeded its attempt limit, or the code does not match the stored hash
     */
    private Otp validateAndGetActiveOtp(String email, String code, OtpPurpose purpose) {
        Otp otp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose).orElseThrow(
                () -> new AuthException("No verification code found. Please request a new one.", HttpStatus.BAD_REQUEST));

        if (otp.isConsumed() || otp.isVerified()) {
            throw new AuthException("This code has already been used. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        if (otp.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new AuthException("This code has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        // Count the attempt only while the code is still open and under the limit, in one update: concurrent
        // guesses each take one of the remaining attempts and can never exceed the limit together.
        Query open = Query.query(Criteria.where("_id").is(otp.getId())
                .and("consumed").is(false).and("verified").is(false)
                .and("attempts").lt(limits.getMaxAttempts()));
        Otp counted = mongoTemplate.findAndModify(open, new Update().inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), Otp.class);
        if (counted == null) {
            throw new AuthException("Too many incorrect attempts. Please request a new code.", HttpStatus.TOO_MANY_REQUESTS);
        }

        if (!passwordEncoder.matches(code, counted.getCodeHash())) {
            throw new AuthException("Incorrect verification code.", HttpStatus.BAD_REQUEST);
        }

        return counted;
    }

    /** Applies {@code update} only if the code is still unused, so a code can succeed for one request only. */
    private void markUsed(Otp otp, Update update) {
        Query unused = Query.query(Criteria.where("_id").is(otp.getId())
                .and("consumed").is(false).and("verified").is(false));
        if (mongoTemplate.updateFirst(unused, update, Otp.class).getModifiedCount() == 0) {
            throw new AuthException("This code has already been used. Please request a new one.", HttpStatus.BAD_REQUEST);
        }
    }
}
