package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.admin.config.SecretCipher;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.GeminiProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keys (temporary, for admin testing): replace the AI provider API keys and the SMTP password at runtime. Keys are
 * write-only: the API only ever returns them masked, they are stored encrypted (see {@link SecretCipher}), and the
 * audit log records them masked.
 * <p>
 * Off unless {@code vk.admin.secret-editing.enabled} is true and {@code VK_CONFIG_ENC_KEY} is set. Turning the flag
 * off removes the feature without touching anything else.
 */
@Component
@Order(60)
public class KeysConfigSection implements ConfigSection {

    private final SecretCipher cipher;
    private final boolean enabled;
    private final Map<String, Secret> secrets = new LinkedHashMap<>();

    public KeysConfigSection(SecretCipher cipher,
                             @Value("${vk.admin.secret-editing.enabled:false}") boolean enabled,
                             DeepInfraProperties deepInfra, GeminiProperties gemini, OpenAIProperties openAi,
                             JavaMailSender mailSender) {
        this.cipher = cipher;
        this.enabled = enabled;
        add("deepinfra.apiKey", "DeepInfra API key", "AI providers", deepInfra::getApiKey, deepInfra::setApiKey);
        add("gemini.apiKey", "Gemini API key", "AI providers", gemini::getApiKey, gemini::setApiKey);
        add("openai.apiKey", "OpenAI API key", "AI providers", openAi::getApiKey, openAi::setApiKey);
        if (mailSender instanceof JavaMailSenderImpl sender) {
            add("mail.password", "SMTP password", "Mail server", sender::getPassword, sender::setPassword);
        }
    }

    @Override
    public String id() {
        return "keys";
    }

    @Override
    public String label() {
        return "Keys";
    }

    @Override
    public String description() {
        return "Replace provider API keys and the SMTP password without a restart. Keys are never shown again "
                + "once saved; reset to go back to the configured ones.";
    }

    @Override
    public boolean available() {
        return enabled && cipher.isConfigured();
    }

    @Override
    public String unavailableReason() {
        if (!enabled) {
            return "Key editing is turned off (vk.admin.secret-editing.enabled).";
        }
        return cipher.isConfigured() ? null : "Set VK_CONFIG_ENC_KEY on the server so keys can be stored encrypted.";
    }

    @Override
    public List<ConfigField> fields(Map<String, String> overrides) {
        List<ConfigField> fields = new ArrayList<>();
        secrets.forEach((key, secret) -> {
            String current = overrides.getOrDefault(key, secret.baseline());
            fields.add(ConfigField.secret(key, secret.label(), secret.group(), SecretCipher.mask(secret.baseline()),
                    SecretCipher.mask(current), overrides.containsKey(key), null));
        });
        return fields;
    }

    @Override
    public void apply(Map<String, String> overrides) {
        Fields.requireKnown(overrides, fields(overrides));
        secrets.forEach((key, secret) -> secret.setter().accept(overrides.getOrDefault(key, secret.baseline())));
    }

    @Override
    public String toStored(String key, String value) {
        return cipher.encrypt(value);
    }

    @Override
    public String fromStored(String key, String value) {
        return cipher.decrypt(value);
    }

    @Override
    public String forAudit(String key, String value) {
        return SecretCipher.mask(value);
    }

    private void add(String key, String label, String group, Supplier<String> getter, Consumer<String> setter) {
        secrets.put(key, new Secret(label, group, getter.get(), setter));
    }

    private record Secret(String label, String group, String baseline, Consumer<String> setter) {
    }
}
