package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties.Call;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generation params: temperature and output token budget per text call site, plus DeepInfra's reasoning effort and
 * JSON-schema mode. Applied to {@link AiGenerationParamsProperties} and {@link DeepInfraProperties}, which the
 * services and the provider read on every request.
 */
@Component
@Order(30)
public class GenerationConfigSection implements ConfigSection {

    private static final List<String> TEMPERATURES = Fields.values("0.0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.7", "1.0");
    private static final List<String> MAX_TOKENS = Fields.values(300, 500, 800, 1000, 2000, 4000, 8000, 10000, 16000);
    private static final List<String> PER_TARGET = Fields.values(100, 200, 300, 400, 600);
    private static final List<String> REASONING = List.of("none", "low", "medium", "high");

    static final String REASONING_EFFORT = "deepinfra.reasoningEffort";
    static final String JSON_SCHEMA = "deepinfra.jsonSchemaEnabled";
    static final String TTS_VOICE = "deepinfra.tts.voice";
    static final String TTS_FORMAT = "deepinfra.tts.responseFormat";

    /** MiMo-V2.5-TTS built-in voices (English, then Chinese); a configured custom voice id is offered too. */
    private static final List<String> TTS_VOICES = List.of("Mia", "Chloe", "Milo", "Dean", "mimo_default",
            "冰糖", "茉莉", "苏打", "白桦");
    /** Only formats narration can store and play. */
    private static final List<String> TTS_FORMATS = List.of("wav", "mp3", "opus");
    static final String BATCH_TEMPERATURE = "promptBatch.temperature";
    static final String BATCH_BASE = "promptBatch.baseMaxTokens";
    static final String BATCH_PER_TARGET = "promptBatch.maxTokensPerTarget";
    static final String BATCH_CAP = "promptBatch.maxTokensCap";

    private final AiGenerationParamsProperties params;
    private final DeepInfraProperties deepInfra;

    /** Call site key to label; the baseline of each is captured at startup. */
    private final Map<String, String> sites = new LinkedHashMap<>();
    private final Map<String, Call> baseCalls = new LinkedHashMap<>();
    private final AiGenerationParamsProperties.PromptBatch baseBatch = new AiGenerationParamsProperties.PromptBatch();
    private final String baseReasoning;
    private final boolean baseJsonSchema;
    private final String baseTtsVoice;
    private final String baseTtsFormat;

    public GenerationConfigSection(AiGenerationParamsProperties params, DeepInfraProperties deepInfra) {
        this.params = params;
        this.deepInfra = deepInfra;
        sites.put("processGeneration", "Recipe process generation");
        sites.put("processEdit", "Recipe process edits");
        sites.put("visualization", "Single step image prompt");
        sites.put("chat", "AI chat");
        sites.forEach((site, label) -> {
            Call live = call(site);
            baseCalls.put(site, new Call(live.getTemperature(), live.getMaxTokens()));
        });
        AiGenerationParamsProperties.PromptBatch batch = params.getPromptBatch();
        baseBatch.setTemperature(batch.getTemperature());
        baseBatch.setBaseMaxTokens(batch.getBaseMaxTokens());
        baseBatch.setMaxTokensPerTarget(batch.getMaxTokensPerTarget());
        baseBatch.setMaxTokensCap(batch.getMaxTokensCap());
        this.baseReasoning = deepInfra.getReasoningEffort() == null ? "none" : deepInfra.getReasoningEffort();
        this.baseJsonSchema = deepInfra.isJsonSchemaEnabled();
        this.baseTtsVoice = deepInfra.getTts().getVoice();
        this.baseTtsFormat = deepInfra.getTts().getResponseFormat();
    }

    @Override
    public String id() {
        return "generation";
    }

    @Override
    public String label() {
        return "Generation";
    }

    @Override
    public String description() {
        return "Sampling temperature and output token budget for each AI text task. Lower temperature is more "
                + "predictable; too small a budget cuts answers off.";
    }

    @Override
    public List<ConfigField> fields(Map<String, String> overrides) {
        List<ConfigField> fields = new ArrayList<>();
        sites.forEach((site, label) -> {
            Call base = baseCalls.get(site);
            fields.add(Fields.select(site + ".temperature", "Temperature", label, TEMPERATURES,
                    temperature(base.getTemperature()), overrides, null));
            fields.add(Fields.select(site + ".maxTokens", "Max output tokens", label, MAX_TOKENS,
                    base.getMaxTokens(), overrides, null));
        });
        String batch = "Step image prompts (batched)";
        fields.add(Fields.select(BATCH_TEMPERATURE, "Temperature", batch, TEMPERATURES,
                temperature(baseBatch.getTemperature()), overrides, null));
        fields.add(Fields.select(BATCH_BASE, "Base output tokens", batch, MAX_TOKENS, baseBatch.getBaseMaxTokens(),
                overrides, "Budget for the batch, before the per-step part."));
        fields.add(Fields.select(BATCH_PER_TARGET, "Output tokens per step", batch, PER_TARGET,
                baseBatch.getMaxTokensPerTarget(), overrides, null));
        fields.add(Fields.select(BATCH_CAP, "Max output tokens", batch, MAX_TOKENS, baseBatch.getMaxTokensCap(),
                overrides, "Upper bound for a whole batch."));

        fields.add(Fields.select(REASONING_EFFORT, "Reasoning effort", "DeepInfra", REASONING, baseReasoning,
                overrides, "More reasoning is slower and uses more tokens."));
        fields.add(Fields.toggle(JSON_SCHEMA, "Strict JSON schema output", "DeepInfra", baseJsonSchema, overrides,
                "Ask the model to follow the response schema exactly."));
        fields.add(Fields.select(TTS_VOICE, "Narration voice (MiMo TTS)", "DeepInfra speech", TTS_VOICES, baseTtsVoice,
                overrides, "Voice of DeepInfra narration models. English: Mia, Chloe, Milo, Dean."));
        fields.add(Fields.select(TTS_FORMAT, "Audio format", "DeepInfra speech", TTS_FORMATS, baseTtsFormat,
                overrides, "WAV gives exact durations; MP3 and Opus are smaller."));
        return fields;
    }

    @Override
    public void apply(Map<String, String> overrides) {
        Fields.requireKnown(overrides, fields(overrides));
        Map<String, Call> calls = new LinkedHashMap<>();
        baseCalls.forEach((site, base) -> calls.put(site, new Call(
                Fields.doubleValue(overrides, site + ".temperature", base.getTemperature()),
                Fields.intValue(overrides, site + ".maxTokens", base.getMaxTokens()))));
        double batchTemperature = Fields.doubleValue(overrides, BATCH_TEMPERATURE, baseBatch.getTemperature());
        int batchBase = Fields.intValue(overrides, BATCH_BASE, baseBatch.getBaseMaxTokens());
        int batchPerTarget = Fields.intValue(overrides, BATCH_PER_TARGET, baseBatch.getMaxTokensPerTarget());
        int batchCap = Fields.intValue(overrides, BATCH_CAP, baseBatch.getMaxTokensCap());
        if (batchCap < batchBase) {
            throw new IllegalArgumentException("The batch's max output tokens can't be below its base output tokens");
        }

        calls.forEach((site, values) -> {
            Call live = call(site);
            live.setTemperature(values.getTemperature());
            live.setMaxTokens(values.getMaxTokens());
        });
        AiGenerationParamsProperties.PromptBatch batch = params.getPromptBatch();
        batch.setTemperature(batchTemperature);
        batch.setBaseMaxTokens(batchBase);
        batch.setMaxTokensPerTarget(batchPerTarget);
        batch.setMaxTokensCap(batchCap);
        deepInfra.setReasoningEffort(overrides.getOrDefault(REASONING_EFFORT, baseReasoning));
        deepInfra.setJsonSchemaEnabled(overrides.containsKey(JSON_SCHEMA)
                ? Boolean.parseBoolean(overrides.get(JSON_SCHEMA)) : baseJsonSchema);
        deepInfra.getTts().setVoice(overrides.getOrDefault(TTS_VOICE, baseTtsVoice));
        deepInfra.getTts().setResponseFormat(overrides.getOrDefault(TTS_FORMAT, baseTtsFormat));
    }

    private Call call(String site) {
        return switch (site) {
            case "processGeneration" -> params.getProcessGeneration();
            case "processEdit" -> params.getProcessEdit();
            case "visualization" -> params.getVisualization();
            case "chat" -> params.getChat();
            default -> throw new IllegalArgumentException("Unknown call site " + site);
        };
    }

    /** One decimal, matching the dropdown values ("0.1", "1.0"). */
    private static String temperature(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
