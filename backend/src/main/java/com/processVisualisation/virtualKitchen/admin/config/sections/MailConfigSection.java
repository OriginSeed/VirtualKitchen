package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.auth.service.AppMailProperties;
import com.processVisualisation.virtualKitchen.auth.service.OtpProperties;
import org.springframework.core.annotation.Order;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OTP &amp; mail: one-time passcode limits, the sender address, and the SMTP host, port and user name. Applied to
 * {@link OtpProperties}, {@link AppMailProperties} and the {@link JavaMailSenderImpl}, all read on the next code or
 * email. The SMTP password is in the Keys section.
 */
@Component
@Order(50)
public class MailConfigSection implements ConfigSection {

    private static final List<String> MINUTES = Fields.values(5, 10, 15, 30, 60);
    private static final List<String> ATTEMPTS = Fields.values(3, 5, 10);
    private static final List<String> PER_WINDOW = Fields.values(3, 5, 10, 20);
    private static final List<String> HOSTS = List.of("smtp.gmail.com", "smtp.office365.com", "smtp.sendgrid.net",
            "email-smtp.us-east-1.amazonaws.com", "localhost");
    private static final List<String> PORTS = Fields.values(25, 465, 587, 1025, 2525);

    static final String EXPIRATION = "otp.expirationMinutes";
    static final String MAX_ATTEMPTS = "otp.maxAttempts";
    static final String RESEND_WINDOW = "otp.resendWindowMinutes";
    static final String MAX_PER_WINDOW = "otp.maxPerWindow";
    static final String FROM = "mail.from";
    static final String HOST = "mail.host";
    static final String PORT = "mail.port";
    static final String USERNAME = "mail.username";

    private final OtpProperties otp;
    private final AppMailProperties appMail;
    /** Null when mail isn't sent through a configurable JavaMailSenderImpl; the SMTP fields are then read-only. */
    private final JavaMailSenderImpl sender;

    private final long baseExpiration;
    private final int baseMaxAttempts;
    private final long baseResendWindow;
    private final int baseMaxPerWindow;
    private final String baseFrom;
    private final String baseHost;
    private final int basePort;
    private final String baseUsername;

    public MailConfigSection(OtpProperties otp, AppMailProperties appMail, JavaMailSender mailSender) {
        this.otp = otp;
        this.appMail = appMail;
        this.sender = mailSender instanceof JavaMailSenderImpl impl ? impl : null;
        this.baseExpiration = otp.getExpirationMinutes();
        this.baseMaxAttempts = otp.getMaxAttempts();
        this.baseResendWindow = otp.getResendWindowMinutes();
        this.baseMaxPerWindow = otp.getMaxPerWindow();
        this.baseFrom = appMail.getFrom();
        this.baseHost = sender != null ? sender.getHost() : null;
        this.basePort = sender != null ? sender.getPort() : 0;
        this.baseUsername = sender != null ? sender.getUsername() : null;
    }

    @Override
    public String id() {
        return "mail";
    }

    @Override
    public String label() {
        return "OTP & Mail";
    }

    @Override
    public String description() {
        return "Limits for sign-in and verification codes, and the mail server the codes are sent through. "
                + "Changes apply to the next code or email.";
    }

    @Override
    public List<ConfigField> fields(Map<String, String> overrides) {
        List<ConfigField> fields = new ArrayList<>();
        String group = "One-time passcodes";
        fields.add(Fields.select(EXPIRATION, "Code valid for (minutes)", group, MINUTES, baseExpiration, overrides, null));
        fields.add(Fields.select(MAX_ATTEMPTS, "Wrong guesses allowed", group, ATTEMPTS, baseMaxAttempts, overrides,
                "Then the code is locked and a new one is needed."));
        fields.add(Fields.select(RESEND_WINDOW, "Resend window (minutes)", group, MINUTES, baseResendWindow, overrides, null));
        fields.add(Fields.select(MAX_PER_WINDOW, "Codes per window", group, PER_WINDOW, baseMaxPerWindow, overrides,
                "Codes one email address may request per window."));

        String mail = "Mail server";
        fields.add(text(FROM, "Sender address", mail, baseFrom, overrides, "The From address of the OTP emails."));
        if (sender != null) {
            fields.add(Fields.select(HOST, "SMTP host", mail, HOSTS, baseHost, overrides, null));
            fields.add(Fields.select(PORT, "SMTP port", mail, PORTS, basePort, overrides,
                    "587 for STARTTLS, 465 for SSL."));
            fields.add(text(USERNAME, "SMTP user name", mail, baseUsername, overrides, null));
        }
        return fields;
    }

    @Override
    public void apply(Map<String, String> overrides) {
        Fields.requireKnown(overrides, fields(overrides));
        long expiration = Fields.longValue(overrides, EXPIRATION, baseExpiration);
        int maxAttempts = Fields.intValue(overrides, MAX_ATTEMPTS, baseMaxAttempts);
        long resendWindow = Fields.longValue(overrides, RESEND_WINDOW, baseResendWindow);
        int maxPerWindow = Fields.intValue(overrides, MAX_PER_WINDOW, baseMaxPerWindow);
        int port = Fields.intValue(overrides, PORT, basePort);

        otp.setExpirationMinutes(expiration);
        otp.setMaxAttempts(maxAttempts);
        otp.setResendWindowMinutes(resendWindow);
        otp.setMaxPerWindow(maxPerWindow);
        appMail.setFrom(overrides.getOrDefault(FROM, baseFrom));
        if (sender != null) {
            sender.setHost(overrides.getOrDefault(HOST, baseHost));
            sender.setPort(port);
            sender.setUsername(overrides.getOrDefault(USERNAME, baseUsername));
        }
    }

    private static ConfigField text(String key, String label, String group, String baseline,
                                    Map<String, String> overrides, String help) {
        return ConfigField.text(key, label, group, baseline, overrides.getOrDefault(key, baseline),
                overrides.containsKey(key), help);
    }
}
