package com.processVisualisation.virtualKitchen.auth.service;

import com.processVisualisation.virtualKitchen.auth.dto.*;
import com.processVisualisation.virtualKitchen.auth.event.UserRegisteredEvent;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.mapper.UserMapper;
import com.processVisualisation.virtualKitchen.auth.model.AuthProvider;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import com.processVisualisation.virtualKitchen.auth.model.User;
import com.processVisualisation.virtualKitchen.auth.model.UserType;
import com.processVisualisation.virtualKitchen.auth.repository.UserRepository;
import com.processVisualisation.virtualKitchen.auth.security.JwtService;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Coordinates the full authentication
 * lifecycle: email/password signup and login, Google OAuth sign-in
 * (delegating token verification to GoogleTokenVerifierService), email
 * verification, OTP-based login, and the forgot/verify/reset password flow
 * (delegating OTP generation and validation to OtpService). Issues JWT access
 * tokens via JwtService on every successful authentication, and publishes a
 * {@link UserRegisteredEvent} for each newly created user (the kitchen feature
 * provisions the user's default kitchen from it).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final GoogleTokenVerifierService googleTokenVerifierService;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminAllowlist adminAllowlist;

    public AuthService(
            UserRepository userRepository,
            SequenceGeneratorService sequenceGeneratorService,
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            OtpService otpService,
            GoogleTokenVerifierService googleTokenVerifierService,
            ApplicationEventPublisher eventPublisher,
            AdminAllowlist adminAllowlist
    ) {
        this.adminAllowlist = adminAllowlist;
        this.userRepository = userRepository;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.otpService = otpService;
        this.googleTokenVerifierService = googleTokenVerifierService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Registers a new local (email/password) user account, provisions a
     * default kitchen for the user, and sends an email verification OTP. The
     * account starts unverified until the OTP is confirmed.
     *
     * @param request the signup payload containing name, email, password, and confirmation
     * @return the created user
     * @throws AuthException if the password and confirmation do not match, or an account already exists for the given email
     */
    public UserResponseDTO signup(SignupRequestDTO request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            log.warn("event=auth_signup_rejected reason=password_mismatch");
            throw new AuthException("Password and confirmation do not match", HttpStatus.BAD_REQUEST);
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            // An unverified local account gets a distinct code, so the client can offer to send a new code.
            boolean unverified = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                    .map(existing -> !existing.isEmailVerified() && existing.getAuthProvider() == AuthProvider.LOCAL)
                    .orElse(false);
            log.warn("event=auth_signup_rejected reason={}", unverified ? "account_unverified" : "account_exists");
            throw new AuthException("An account with this email already exists", HttpStatus.CONFLICT,
                    unverified ? ErrorCode.EMAIL_NOT_VERIFIED : null);
        }

        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmailVerified(false);
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setUserType(UserType.USER);
        user.setId(sequenceGeneratorService.generateSequence(User.SEQUENCE_NAME));

        User saved = userRepository.save(user);
        announceRegistration(saved);
        log.info("event=auth_signup_completed userId={} provider={}", saved.getId(), AuthProvider.LOCAL);

        otpService.generateAndSendOtp(saved.getEmail(), OtpPurpose.EMAIL_VERIFICATION);
        logOtpSent(saved.getId(), OtpPurpose.EMAIL_VERIFICATION);

        return userMapper.toDTO(saved);
    }

    /**
     * Authenticates a user with email and password credentials and issues an
     * access token.
     *
     * @param request the login payload containing email and password
     * @return the issued authentication token and user info
     * @throws AuthException if no account matches the email/password, or the account email has not been verified
     */
    public AuthResponseDTO login(LoginRequestDTO request) {
        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase()).orElse(null);
        if (user == null) {
            log.warn("event=auth_login_failed method=password reason=unknown_account");
            throw new AuthException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("event=auth_login_failed method=password reason={} userId={}",
                    user.getPasswordHash() == null ? "no_password_set" : "bad_credentials", user.getId());
            throw new AuthException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        if (!user.isEmailVerified()) {
            log.warn("event=auth_login_failed method=password reason=email_unverified userId={}", user.getId());
            throw new AuthException("Please verify your email before signing in", HttpStatus.FORBIDDEN,
                    ErrorCode.EMAIL_NOT_VERIFIED);
        }

        return buildAuthResponse(user, "password");
    }

    /**
     * Authenticates or provisions a user via a Google OAuth ID token. If no
     * account exists for the verified Google email, a new account is created
     * (already email-verified) and a default kitchen is provisioned for it.
     * If an existing local account matches the email but has not yet been
     * linked to Google, it is linked to this Google identity and marked
     * verified. Issues an access token on success.
     *
     * @param request the payload containing the Google ID token to verify
     * @return the issued authentication token and user info
     * @throws AuthException if the Google token is invalid, expired, not issued for this application, or its email is unverified
     */
    public AuthResponseDTO googleAuth(GoogleAuthRequestDTO request) {
        GoogleUserInfo googleUser = googleTokenVerifierService.verify(request.getIdToken());

        User user = userRepository.findByEmail(googleUser.email().trim().toLowerCase()).orElse(null);

        if (user == null) {
            user = new User();
            user.setName(googleUser.name());
            user.setEmail(googleUser.email().trim().toLowerCase());
            user.setEmailVerified(true);
            user.setAuthProvider(AuthProvider.GOOGLE);
            user.setGoogleId(googleUser.googleId());
            user.setUserType(UserType.USER);
            user.setId(sequenceGeneratorService.generateSequence(User.SEQUENCE_NAME));

            user = userRepository.save(user);
            announceRegistration(user);
            log.info("event=auth_signup_completed userId={} provider={}", user.getId(), AuthProvider.GOOGLE);
        } else if (user.getGoogleId() == null) {
            // Link the existing (email/password) account to this Google identity instead of duplicating it.
            user.setGoogleId(googleUser.googleId());
            user.setEmailVerified(true);
            user = userRepository.save(user);
            log.info("event=auth_google_account_linked userId={}", user.getId());
        }

        return buildAuthResponse(user, "google");
    }

    /**
     * Sends a one-time passcode to the given email address to verify account
     * ownership after signup.
     *
     * @param email the destination email address
     * @throws AuthException if no account exists for the given email, or the email is already verified
     */
    public void sendEmailVerificationOtp(String email) {
        User user = getUserByEmailOrThrow(email);

        if (user.isEmailVerified()) {
            throw new AuthException("This email is already verified", HttpStatus.BAD_REQUEST);
        }

        otpService.generateAndSendOtp(user.getEmail(), OtpPurpose.EMAIL_VERIFICATION);
        logOtpSent(user.getId(), OtpPurpose.EMAIL_VERIFICATION);
    }

    /**
     * Verifies the email-verification OTP for the given account, marks the
     * account as email-verified, and issues an access token.
     *
     * @param request the payload containing the email address and OTP code to verify
     * @return the issued authentication token and user info
     * @throws AuthException if no account exists for the given email, or the OTP is missing, expired, already used, or does not match
     */
    public AuthResponseDTO verifyEmailOtp(VerifyOtpRequestDTO request) {
        User user = getUserByEmailOrThrow(request.getEmail());

        otpService.verifyAndConsume(user.getEmail(), request.getOtp(), OtpPurpose.EMAIL_VERIFICATION);

        user.setEmailVerified(true);
        user = userRepository.save(user);
        log.info("event=auth_email_verified userId={}", user.getId());

        return buildAuthResponse(user, "email_verification_otp");
    }

    /**
     * Sends a one-time passcode to the given email address for signing in
     * without a password.
     *
     * @param email the destination email address
     * @throws AuthException if no account exists for the given email
     */
    public void sendLoginOtp(String email) {
        User user = getUserByEmailOrThrow(email);
        otpService.generateAndSendOtp(email.trim().toLowerCase(), OtpPurpose.LOGIN);
        logOtpSent(user.getId(), OtpPurpose.LOGIN);
    }

    /**
     * Verifies a login OTP and, on success, authenticates the user (also
     * marking the account email-verified if it was not already) and issues an
     * access token.
     *
     * @param request the payload containing the email address and OTP code to verify
     * @return the issued authentication token and user info
     * @throws AuthException if no account exists for the given email, or the OTP is missing, expired, already used, or does not match
     */
    public AuthResponseDTO verifyLoginOtp(VerifyOtpRequestDTO request) {
        User user = getUserByEmailOrThrow(request.getEmail());

        try {
            otpService.verifyAndConsume(user.getEmail(), request.getOtp(), OtpPurpose.LOGIN);
        } catch (AuthException e) {
            log.warn("event=auth_login_failed method=login_otp reason=otp_rejected userId={}", user.getId());
            throw e;
        }

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user = userRepository.save(user);
            log.info("event=auth_email_verified userId={}", user.getId());
        }

        return buildAuthResponse(user, "login_otp");
    }

    /**
     * Sends a one-time passcode to the given email address to begin a
     * password reset flow.
     *
     * @param email the destination email address
     * @throws AuthException if no account exists for the given email
     */
    public void sendPasswordResetOtp(String email) {
        User user = getUserByEmailOrThrow(email);
        otpService.generateAndSendOtp(email.trim().toLowerCase(), OtpPurpose.PASSWORD_RESET);
        logOtpSent(user.getId(), OtpPurpose.PASSWORD_RESET);
    }

    /**
     * Verifies the password-reset OTP without consuming it, confirming the
     * caller is allowed to proceed to set a new password.
     *
     * @param request the payload containing the email address and OTP code to verify
     * @throws AuthException if no account exists for the given email, or the OTP is missing, expired, already used, or does not match
     */
    public void verifyPasswordResetOtp(VerifyOtpRequestDTO request) {
        User user = getUserByEmailOrThrow(request.getEmail());
        otpService.verifyOnly(user.getEmail(), request.getOtp(), OtpPurpose.PASSWORD_RESET);
    }

    /**
     * Sets a new password for the account, requiring that a password-reset
     * OTP for this email has already been verified via verifyPasswordResetOtp.
     *
     * @param request the payload containing the email address, new password, and confirmation
     * @throws AuthException if the new password and confirmation do not match, no account exists for the given email, or no verified password-reset OTP is pending (or it has expired)
     */
    public void resetPassword(ResetPasswordRequestDTO request) {
        if (!request.getNewPassword().equals(request.getConfirmNewPassword())) {
            throw new AuthException("Password and confirmation do not match", HttpStatus.BAD_REQUEST);
        }

        User user = getUserByEmailOrThrow(request.getEmail());

        otpService.consumeVerified(user.getEmail(), OtpPurpose.PASSWORD_RESET);

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("event=auth_password_reset_completed userId={}", user.getId());
    }

    /**
     * Fetches the profile of the currently authenticated user.
     *
     * @param userId the identifier of the authenticated user
     * @return the matching user
     * @throws AuthException if no user exists with the given id
     */
    public UserResponseDTO getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found", HttpStatus.UNAUTHORIZED));

        return userMapper.toDTO(user);
    }

    /**
     * Looks up a user by email, normalizing case and surrounding whitespace.
     *
     * @param email the email address to look up
     * @return the matching user
     * @throws AuthException if no account exists for the given email
     */
    private User getUserByEmailOrThrow(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new AuthException("No account found with this email", HttpStatus.NOT_FOUND));
    }

    /**
     * Announces a newly created account (via signup or first-time Google sign-in), so other features can set
     * the user up; the kitchen feature provisions the user's default kitchen. Listeners run synchronously.
     *
     * @param user the newly created user
     */
    private void announceRegistration(User user) {
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId(), user.getName()));
    }

    /** One line per OTP handed to the mail sender: the purpose and account id only, never the address or code. */
    private static void logOtpSent(Long userId, OtpPurpose purpose) {
        log.info("event=auth_otp_sent userId={} purpose={}", userId, purpose);
    }

    /**
     * Builds the authentication response for a user by issuing a fresh JWT
     * access token and mapping the user entity to its response DTO.
     *
     * @param user   the user to build an authentication response for
     * @param method how the user authenticated, for the {@code auth_login_succeeded} log line
     * @return the issued authentication token paired with the user profile
     */
    private AuthResponseDTO buildAuthResponse(User user, String method) {
        if (user.isEmailVerified() && user.getUserType() != UserType.ADMIN && adminAllowlist.contains(user.getEmail())) {
            user.setUserType(UserType.ADMIN);
            user = userRepository.save(user);
            log.info("event=user_promoted_to_admin userId={} reason=vk.admin.emails", user.getId());
        }
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getUserType());
        log.info("event=auth_login_succeeded userId={} method={} userType={}", user.getId(), method, user.getUserType());

        return AuthResponseDTO.builder()
                .token(token)
                .user(userMapper.toDTO(user))
                .build();
    }
}
