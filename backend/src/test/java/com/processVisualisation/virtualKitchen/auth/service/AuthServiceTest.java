package com.processVisualisation.virtualKitchen.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.processVisualisation.virtualKitchen.auth.dto.LoginRequestDTO;
import com.processVisualisation.virtualKitchen.auth.dto.SignupRequestDTO;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.mapper.UserMapper;
import com.processVisualisation.virtualKitchen.auth.model.AuthProvider;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import com.processVisualisation.virtualKitchen.auth.model.User;
import com.processVisualisation.virtualKitchen.auth.model.UserType;
import com.processVisualisation.virtualKitchen.auth.repository.UserRepository;
import com.processVisualisation.virtualKitchen.auth.security.JwtService;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.common.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The audit lines {@link AuthService} writes for sign-in: ids and reason codes only, never the email or password. */
class AuthServiceTest {

    private static final String EMAIL = "cook@example.test";
    private static final String PASSWORD = "s3cret-pass";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);
    private final OtpService otpService = mock(OtpService.class);
    private final AuthService service = new AuthService(users, mock(SequenceGeneratorService.class), new UserMapper(),
            encoder, jwt, otpService, mock(GoogleTokenVerifierService.class),
            mock(ApplicationEventPublisher.class), new AdminAllowlist(""));

    @Test
    void wrongPassword_logsLoginFailedWithReasonCodeAndNoEmail() {
        seedUser(true);
        when(encoder.matches(anyString(), anyString())).thenReturn(false);

        try (LogCapture logs = LogCapture.of(AuthService.class)) {
            assertThrows(AuthException.class, () -> service.login(login()));

            List<ILoggingEvent> failed = logs.events("auth_login_failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(failed.get(0).getFormattedMessage())
                    .contains("reason=bad_credentials")
                    .contains("userId=5");
            assertNoCredentials(logs);
            assertThat(logs.events("auth_login_succeeded")).isEmpty();
        }
    }

    @Test
    void unknownAccount_logsLoginFailedWithoutTheEmail() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

        try (LogCapture logs = LogCapture.of(AuthService.class)) {
            assertThrows(AuthException.class, () -> service.login(login()));

            List<ILoggingEvent> failed = logs.events("auth_login_failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(failed.get(0).getFormattedMessage()).contains("reason=unknown_account");
            assertNoCredentials(logs);
        }
    }

    @Test
    void correctPassword_logsLoginSucceededWithUserIdOnly() {
        seedUser(true);
        when(encoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwt.generateToken(any(), any(), any())).thenReturn("token");

        try (LogCapture logs = LogCapture.of(AuthService.class)) {
            service.login(login());

            List<ILoggingEvent> succeeded = logs.events("auth_login_succeeded");
            assertThat(succeeded).hasSize(1);
            assertThat(succeeded.get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(succeeded.get(0).getFormattedMessage()).contains("userId=5").contains("method=password");
            assertNoCredentials(logs);
        }
    }

    @Test
    void unverifiedLogin_keeps403AndMessage_andCarriesEmailNotVerifiedCode() {
        seedUser(false);
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        AuthException error = assertThrows(AuthException.class, () -> service.login(login()));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error.getMessage()).isEqualTo("Please verify your email before signing in");
        assertThat(error.getCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    void signupForUnverifiedLocalAccount_keeps409_andCarriesEmailNotVerifiedCode() {
        seedUser(false);
        when(users.existsByEmail(EMAIL)).thenReturn(true);

        AuthException error = assertThrows(AuthException.class, () -> service.signup(signup()));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getMessage()).isEqualTo("An account with this email already exists");
        assertThat(error.getCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    void signupForVerifiedAccount_staysAPlainConflict() {
        seedUser(true);
        when(users.existsByEmail(EMAIL)).thenReturn(true);

        AuthException error = assertThrows(AuthException.class, () -> service.signup(signup()));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void sendEmailVerificationOtp_forUnverifiedAccount_sendsANewCode() {
        seedUser(false);

        service.sendEmailVerificationOtp(EMAIL);

        verify(otpService).generateAndSendOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION);
    }

    private static SignupRequestDTO signup() {
        SignupRequestDTO request = new SignupRequestDTO();
        request.setName("Cook");
        request.setEmail(EMAIL);
        request.setPassword(PASSWORD);
        request.setConfirmPassword(PASSWORD);
        return request;
    }

    private void assertNoCredentials(LogCapture logs) {
        String all = logs.allMessages();
        assertThat(all).doesNotContain(EMAIL).doesNotContain("example.test").doesNotContain(PASSWORD);
    }

    private void seedUser(boolean verified) {
        User user = new User();
        user.setId(5L);
        user.setEmail(EMAIL);
        user.setPasswordHash("hash");
        user.setEmailVerified(verified);
        user.setUserType(UserType.USER);
        user.setAuthProvider(AuthProvider.LOCAL);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private static LoginRequestDTO login() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setEmail(EMAIL);
        request.setPassword(PASSWORD);
        return request;
    }
}
