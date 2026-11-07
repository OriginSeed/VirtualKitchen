package com.processVisualisation.virtualKitchen.auth.service;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.model.Otp;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import com.processVisualisation.virtualKitchen.auth.repository.OtpRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

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
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository otpRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private final OtpProperties limits;

    public OtpService(OtpRepository otpRepository, EmailService emailService, PasswordEncoder passwordEncoder,
                      OtpProperties limits) {
        this.otpRepository = otpRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.limits = limits;
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
        long recentCount = otpRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, LocalDateTime.now().minusMinutes(limits.getResendWindowMinutes()));

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
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(limits.getExpirationMinutes()));

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
        otp.setVerified(true);
        otp.setConsumed(true);
        otpRepository.save(otp);
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
        otp.setVerified(true);
        otpRepository.save(otp);
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

        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException("Verification has expired. Please request a new code.", HttpStatus.BAD_REQUEST);
        }

        otp.setConsumed(true);
        otpRepository.save(otp);
    }

    /**
     * Fetches the most recent OTP for the given email and purpose, checks it
     * is unconsumed/unverified, unexpired, and under its attempt limit,
     * increments its attempt counter, and verifies the submitted code against
     * the stored hash.
     *
     * @param email the email address the OTP was issued for
     * @param code the plaintext OTP code submitted by the caller
     * @param purpose the purpose the OTP must have been issued for
     * @return the validated OTP entity
     * @throws AuthException if no active OTP is found, it is already used, has expired, has exceeded its attempt limit, or the code does not match the stored hash
     */
    private Otp validateAndGetActiveOtp(String email, String code, OtpPurpose purpose) {
        Optional<Otp> latest = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);

        Otp otp = latest.orElseThrow(
                () -> new AuthException("No verification code found. Please request a new one.", HttpStatus.BAD_REQUEST));

        if (otp.isConsumed() || otp.isVerified()) {
            throw new AuthException("This code has already been used. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException("This code has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        if (otp.getAttempts() >= limits.getMaxAttempts()) {
            throw new AuthException("Too many incorrect attempts. Please request a new code.", HttpStatus.TOO_MANY_REQUESTS);
        }

        otp.setAttempts(otp.getAttempts() + 1);
        otpRepository.save(otp);

        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            throw new AuthException("Incorrect verification code.", HttpStatus.BAD_REQUEST);
        }

        return otp;
    }
}
