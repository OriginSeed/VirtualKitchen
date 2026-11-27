package com.processVisualisation.virtualKitchen.auth.service;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Sends OTP notification emails through Resend ({@link ResendEmailClient}), using the from address in
 * app.mail.from and subject/body text tailored to the OTP purpose (email verification, login, or password reset).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ResendEmailClient emailClient;

    private final AppMailProperties mailProperties;

    public EmailService(ResendEmailClient emailClient, AppMailProperties mailProperties) {
        this.emailClient = emailClient;
        this.mailProperties = mailProperties;
    }

    /**
     * Builds and sends an OTP notification email via the configured mail
     * sender, choosing subject and body text based on the OTP purpose.
     *
     * @param to the destination email address
     * @param otp the one-time passcode to include in the email body
     * @param purpose the reason the OTP was generated, used to select subject/body wording
     * @throws AuthException if the email provider is not configured or fails to accept the message
     */
    public void sendOtpEmail(String to, String otp, OtpPurpose purpose) {
        try {
            String messageId = emailClient.send(mailProperties.getFrom(), to, subjectFor(purpose), bodyFor(otp, purpose));
            log.info("event=email_sent provider=resend purpose={} recipientDomain={} messageId={}",
                    purpose, domainOf(to), messageId);
        } catch (EmailDeliveryException e) {
            log.error("event=email_send_failed provider=resend purpose={} recipientDomain={} reason={} status={} providerError={}",
                    purpose, domainOf(to), e.getReason(), e.getStatus(), e.getProviderError());
            throw new AuthException("Unable to send verification email. Please try again later.", HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * Selects the email subject line for the given OTP purpose.
     *
     * @param purpose the reason the OTP was generated
     * @return the subject line to use
     */
    private String subjectFor(OtpPurpose purpose) {
        return switch (purpose) {
            case EMAIL_VERIFICATION -> "Verify your Virtual Kitchen email";
            case PASSWORD_RESET -> "Reset your Virtual Kitchen password";
            case LOGIN -> "Your Virtual Kitchen sign-in code";
        };
    }

    /**
     * Builds the email body text embedding the OTP code, worded according to
     * the OTP purpose.
     *
     * @param otp the one-time passcode to include in the body
     * @param purpose the reason the OTP was generated
     * @return the email body text
     */
    private String bodyFor(String otp, OtpPurpose purpose) {
        String action = switch (purpose) {
            case EMAIL_VERIFICATION -> "verify your email";
            case PASSWORD_RESET -> "reset your password";
            case LOGIN -> "sign in";
        };

        return "Use the code below to " + action + " on Virtual Kitchen:\n\n"
                + otp
                + "\n\nThis code expires shortly and can only be used once. "
                + "If you did not request this, you can safely ignore this email.";
    }

    /** Only the domain part of an address is logged, so failures can be diagnosed without recording PII. */
    private static String domainOf(String address) {
        if (address == null) {
            return "unknown";
        }
        int at = address.lastIndexOf('@');
        return at >= 0 && at < address.length() - 1 ? address.substring(at + 1) : "unknown";
    }
}
