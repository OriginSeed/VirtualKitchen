package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.auth.service.AppMailProperties;
import com.processVisualisation.virtualKitchen.auth.service.OtpProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** After the switch to Resend, SMTP overrides stored before it must not block the section's other overrides. */
class MailConfigSectionTest {

    private final OtpProperties otp = new OtpProperties();
    private final AppMailProperties mail = new AppMailProperties();

    private MailConfigSection section() {
        mail.setFrom("Kitchen <no-reply@kitchen.example>");
        return new MailConfigSection(otp, mail);
    }

    @Test
    void offersOnlyOtpLimitsAndTheSenderAddress() {
        assertThat(section().fields(Map.of()).stream().map(ConfigField::key))
                .containsExactly(MailConfigSection.EXPIRATION, MailConfigSection.MAX_ATTEMPTS,
                        MailConfigSection.RESEND_WINDOW, MailConfigSection.MAX_PER_WINDOW, MailConfigSection.FROM);
    }

    @Test
    void storedSmtpOverridesAreIgnored_andTheRestStillApply() {
        MailConfigSection section = section();

        section.apply(Map.of("mail.host", "smtp.gmail.com", "mail.port", "587", "mail.username", "old",
                MailConfigSection.FROM, "Other <hi@kitchen.example>"));

        assertThat(mail.getFrom()).isEqualTo("Other <hi@kitchen.example>");
    }

    @Test
    void unknownKeysAreStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> section().apply(Map.of("mail.bogus", "x")));
    }
}
