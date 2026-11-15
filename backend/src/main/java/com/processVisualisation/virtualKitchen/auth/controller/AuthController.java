package com.processVisualisation.virtualKitchen.auth.controller;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;

import com.processVisualisation.virtualKitchen.auth.dto.*;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.service.AuthService;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * REST controller exposing the authentication resource at /api/v1/auth.
 * Handles the full authentication lifecycle for the Virtual Kitchen application:
 * email/password signup and login, Google OAuth sign-in, email verification and
 * login via one-time passcodes (OTP), the forgot/reset password flow, and lookup
 * of the currently authenticated user. All endpoints wrap their payload in a
 * common ApiResponse envelope. Endpoints other than /me are expected to be
 * publicly reachable (pre-authentication); /me relies on a principal having
 * already been populated in the SecurityContextHolder (e.g. by the JWT
 * authentication filter) and rejects unauthenticated callers.
 */
@RestController
@LogAction("auth")
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Registers a new user account with email and password credentials and
     * triggers an email verification OTP.
     *
     * @param request the signup payload containing the new users credentials and profile data
     * @return a 201 Created response wrapping the newly created user
     * @throws AuthException if an account for the given email already exists or the signup cannot be completed
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponseDTO>> signup(@Valid @RequestBody SignupRequestDTO request) {
        UserResponseDTO response = authService.signup(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                buildResponse(true, "Account created. Please verify the code sent to your email.", response));
    }

    /**
     * Authenticates a user with email and password credentials and issues an
     * access token on success.
     *
     * @param request the login payload containing the users email and password
     * @return a 200 OK response wrapping the issued authentication tokens and user info
     * @throws AuthException if the credentials are invalid or the account is not eligible to log in (e.g. unverified)
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponseDTO>> login(@Valid @RequestBody LoginRequestDTO request) {
        AuthResponseDTO response = authService.login(request);

        return ResponseEntity.ok(buildResponse(true, "Logged in successfully", response));
    }

    /**
     * Authenticates or provisions a user via a Google OAuth ID token and issues
     * an access token on success.
     *
     * @param request the payload containing the Google ID token to verify
     * @return a 200 OK response wrapping the issued authentication tokens and user info
     * @throws AuthException if the Google token is invalid, expired, or cannot be verified
     */
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponseDTO>> google(@Valid @RequestBody GoogleAuthRequestDTO request) {
        AuthResponseDTO response = authService.googleAuth(request);

        return ResponseEntity.ok(buildResponse(true, "Logged in successfully", response));
    }

    /**
     * Sends a one-time passcode to the given email address for the purpose of
     * verifying account ownership after signup.
     *
     * @param request the payload containing the destination email address
     * @return a 200 OK response acknowledging that the code was sent
     * @throws AuthException if the email does not correspond to an account awaiting verification
     */
    @PostMapping("/email/send-otp")
    public ResponseEntity<ApiResponse<Void>> sendEmailVerificationOtp(@Valid @RequestBody EmailRequestDTO request) {
        authService.sendEmailVerificationOtp(request.getEmail());

        return ResponseEntity.ok(buildResponse(true, "Verification code sent to your email.", null));
    }

    /**
     * Verifies the email-verification OTP previously sent to the user and, on
     * success, activates the account and issues an access token.
     *
     * @param request the payload containing the email address and OTP code to verify
     * @return a 200 OK response wrapping the issued authentication tokens and user info
     * @throws AuthException if the OTP is missing, expired, already used, or does not match
     */
    @PostMapping("/email/verify-otp")
    public ResponseEntity<ApiResponse<AuthResponseDTO>> verifyEmailOtp(@Valid @RequestBody VerifyOtpRequestDTO request) {
        AuthResponseDTO response = authService.verifyEmailOtp(request);

        return ResponseEntity.ok(buildResponse(true, "Email verified successfully", response));
    }

    /**
     * Sends a one-time passcode to the given email address for the purpose of
     * signing in without a password.
     *
     * @param request the payload containing the destination email address
     * @return a 200 OK response acknowledging that the code was sent
     * @throws AuthException if no account exists for the given email or the account cannot use OTP login
     */
    @PostMapping("/login/otp/send")
    public ResponseEntity<ApiResponse<Void>> sendLoginOtp(@Valid @RequestBody EmailRequestDTO request) {
        authService.sendLoginOtp(request.getEmail());

        return ResponseEntity.ok(buildResponse(true, "Sign-in code sent to your email.", null));
    }

    /**
     * Verifies a login OTP and, on success, authenticates the user and issues
     * an access token.
     *
     * @param request the payload containing the email address and OTP code to verify
     * @return a 200 OK response wrapping the issued authentication tokens and user info
     * @throws AuthException if the OTP is missing, expired, already used, or does not match
     */
    @PostMapping("/login/otp/verify")
    public ResponseEntity<ApiResponse<AuthResponseDTO>> verifyLoginOtp(@Valid @RequestBody VerifyOtpRequestDTO request) {
        AuthResponseDTO response = authService.verifyLoginOtp(request);

        return ResponseEntity.ok(buildResponse(true, "Logged in successfully", response));
    }

    /**
     * Sends a one-time passcode to the given email address to begin a password
     * reset flow.
     *
     * @param request the payload containing the destination email address
     * @return a 200 OK response acknowledging that the code was sent
     * @throws AuthException if no account exists for the given email
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody EmailRequestDTO request) {
        authService.sendPasswordResetOtp(request.getEmail());

        return ResponseEntity.ok(buildResponse(true, "Password reset code sent to your email.", null));
    }

    /**
     * Verifies the password-reset OTP, confirming the caller is allowed to
     * proceed to set a new password.
     *
     * @param request the payload containing the email address and OTP code to verify
     * @return a 200 OK response acknowledging that the code was verified
     * @throws AuthException if the OTP is missing, expired, already used, or does not match
     */
    @PostMapping("/password/verify-otp")
    public ResponseEntity<ApiResponse<Void>> verifyPasswordResetOtp(@Valid @RequestBody VerifyOtpRequestDTO request) {
        authService.verifyPasswordResetOtp(request);

        return ResponseEntity.ok(buildResponse(true, "Code verified. You can now set a new password.", null));
    }

    /**
     * Sets a new password for the account after a successful password-reset
     * OTP verification.
     *
     * @param request the payload containing the email address, verified OTP context, and new password
     * @return a 200 OK response acknowledging that the password was updated
     * @throws AuthException if the reset cannot be completed (e.g. no verified reset in progress)
     */
    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
        authService.resetPassword(request);

        return ResponseEntity.ok(buildResponse(true, "Password updated. Please sign in.", null));
    }

    /**
     * Returns the profile of the currently authenticated user, as resolved from
     * the security context populated for this request (e.g. by the JWT
     * authentication filter).
     *
     * @return a 200 OK response wrapping the current users profile
     * @throws AuthException if no authenticated principal is present on the request (HTTP 401)
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponseDTO>> me() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;

        if (!(principal instanceof Long userId)) {
            throw new AuthException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }

        UserResponseDTO response = authService.getCurrentUser(userId);

        return ResponseEntity.ok(buildResponse(true, "User fetched successfully", response));
    }

    /**
     * Builds the common ApiResponse envelope used by every endpoint in this
     * controller.
     *
     * @param success whether the operation succeeded
     * @param message a human-readable status message
     * @param data the response payload, or null for endpoints with no body
     * @param <T> the type of the response payload
     * @return the assembled response envelope, timestamped at build time
     */
    private <T> ApiResponse<T> buildResponse(boolean success, String message, T data) {
        return ApiResponse.<T>builder()
                .success(success)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
