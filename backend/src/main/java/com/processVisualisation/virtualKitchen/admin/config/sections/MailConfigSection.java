package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.auth.service.AppMailProperties;
import com.processVisualisation.virtualKitchen.auth.service.OtpProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OTP &amp; mail: one-time passcode limits and the sender address. Applied to {@link OtpProperties} and
 * {@link AppMailProperties}, both read on the next code or email. Mail goes out through Resend; its API key is in
 * the Keys section.
 */
@Component
@Order(50)
public class MailConfigSection implements ConfigSection {

    private static final List<String> MINUTES = Fields.values(5, 10, 15, 30, 60);
    private static final List<String> ATTEMPTS = Fields.values(3, 5, 10);
    private static final List<String> PER_WINDOW = Fields.values(3, 5, 10, 20);

    static final String EXPIRATION = "otp.expirationMinutes";
    static final String MAX_ATTEMPTS = "otp.maxAttempts";
    static final String RESEND_WINDOW = "otp.resendWindowMinutes";
    static final String MAX_PER_WINDOW = "otp.maxPerWindow";
    static final String FROM = "mail.from";

    /** SMTP settings from before the switch to Resend; stored overrides for them are ignored, not rejected. */
    static final Set<String> RETIRED = Set.of("mail.host", "mail.port", "mail.username");

    private final OtpProperties otp;
    private final AppMailProperties appMail;

    private final long baseExpiration;
    private final int baseMaxAttempts;
    private final long baseResendWindow;
    private final int baseMaxPerWindow;
    private final String baseFrom;

    public MailConfigSection(OtpProperties otp, AppMailProperties appMail) {
        this.otp = otp;
        this.appMail = appMail;
        this.baseExpiration = otp.getExpirationMinutes();
        this.baseMaxAttempts = otp.getMaxAttempts();
        this.baseResendWindow = otp.getResendWindowMinutes();
        this.baseMaxPerWindow = otp.getMaxPerWindow();
        this.baseFrom = appMail.getFrom();
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
        return "Limits for sign-in and verification codes, and the address the codes are sent from. "
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

        fields.add(text(FROM, "Sender address", "Mail", baseFrom, overrides,
                "The From address of the OTP emails. Its domain must be verified in Resend."));
        return fields;
    }

    @Override
    public void apply(Map<String, String> storedOverrides) {
        Map<String, String> overrides = new LinkedHashMap<>(storedOverrides);
        overrides.keySet().removeAll(RETIRED);
        Fields.requireKnown(overrides, fields(overrides));
        long expiration = Fields.longValue(overrides, EXPIRATION, baseExpiration);
        int maxAttempts = Fields.intValue(overrides, MAX_ATTEMPTS, baseMaxAttempts);
        long resendWindow = Fields.longValue(overrides, RESEND_WINDOW, baseResendWindow);
        int maxPerWindow = Fields.intValue(overrides, MAX_PER_WINDOW, baseMaxPerWindow);

        otp.setExpirationMinutes(expiration);
        otp.setMaxAttempts(maxAttempts);
        otp.setResendWindowMinutes(resendWindow);
        otp.setMaxPerWindow(maxPerWindow);
        appMail.setFrom(overrides.getOrDefault(FROM, baseFrom));
    }

    private static ConfigField text(String key, String label, String group, String baseline,
                                    Map<String, String> overrides, String help) {
        return ConfigField.text(key, label, group, baseline, overrides.getOrDefault(key, baseline),
                overrides.containsKey(key), help);
    }
}
