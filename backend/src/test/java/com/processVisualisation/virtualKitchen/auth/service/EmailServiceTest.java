package com.processVisualisation.virtualKitchen.auth.service;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The OTP email content stays as it was under SMTP, and provider failures reach clients only as a generic 502. */
class EmailServiceTest {

    private static final String FROM = "Virtual Kitchen <no-reply@kitchen.example>";
    private static final String TO = "cook@example.test";
    private static final String OTP = "482913";

    private final ResendEmailClient client = mock(ResendEmailClient.class);
    private EmailService service;

    @BeforeEach
    void setUp() {
        AppMailProperties mail = new AppMailProperties();
        mail.setFrom(FROM);
        service = new EmailService(client, mail);
    }

    @Test
    void emailVerification_sendsTheSameSubjectAndBodyAsBefore() {
        when(client.send(any(), any(), any(), any())).thenReturn("msg_1");

        service.sendOtpEmail(TO, OTP, OtpPurpose.EMAIL_VERIFICATION);

        verify(client).send(FROM, TO, "Verify your Virtual Kitchen email",
                "Use the code below to verify your email on Virtual Kitchen:\n\n" + OTP
                        + "\n\nThis code expires shortly and can only be used once. "
                        + "If you did not request this, you can safely ignore this email.");
    }

    @Test
    void passwordReset_andLogin_keepTheirSubjects() {
        service.sendOtpEmail(TO, OTP, OtpPurpose.PASSWORD_RESET);
        verify(client).send(any(), any(), org.mockito.ArgumentMatchers.eq("Reset your Virtual Kitchen password"),
                org.mockito.ArgumentMatchers.contains("reset your password"));

        service.sendOtpEmail(TO, OTP, OtpPurpose.LOGIN);
        verify(client).send(any(), any(), org.mockito.ArgumentMatchers.eq("Your Virtual Kitchen sign-in code"),
                org.mockito.ArgumentMatchers.contains("sign in"));
    }

    @Test
    void success_logsDomainAndMessageIdButNeverTheAddressOrCode() {
        when(client.send(any(), any(), any(), any())).thenReturn("msg_1");

        try (LogCapture logs = LogCapture.of(EmailService.class)) {
            service.sendOtpEmail(TO, OTP, OtpPurpose.LOGIN);

            assertThat(logs.events("email_sent")).hasSize(1);
            assertThat(logs.allMessages())
                    .contains("recipientDomain=example.test").contains("messageId=msg_1")
                    .doesNotContain(TO).doesNotContain(OTP);
        }
    }

    @Test
    void providerFailure_throwsGeneric502AndLogsReasonWithoutSecrets() {
        when(client.send(any(), any(), any(), any())).thenThrow(
                new EmailDeliveryException(EmailDeliveryException.Reason.REJECTED, 403, "validation_error", null));

        try (LogCapture logs = LogCapture.of(EmailService.class)) {
            AuthException error = assertThrows(AuthException.class,
                    () -> service.sendOtpEmail(TO, OTP, OtpPurpose.EMAIL_VERIFICATION));

            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getMessage()).isEqualTo("Unable to send verification email. Please try again later.");
            assertThat(logs.events("email_send_failed")).hasSize(1);
            assertThat(logs.allMessages()).contains("reason=REJECTED").contains("status=403")
                    .doesNotContain(TO).doesNotContain(OTP);
        }
    }

    @Test
    void missingConfiguration_throwsGeneric502() {
        when(client.send(any(), any(), any(), any())).thenThrow(
                new EmailDeliveryException(EmailDeliveryException.Reason.NOT_CONFIGURED, 0, null, null));

        AuthException error = assertThrows(AuthException.class,
                () -> service.sendOtpEmail(TO, OTP, OtpPurpose.PASSWORD_RESET));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
