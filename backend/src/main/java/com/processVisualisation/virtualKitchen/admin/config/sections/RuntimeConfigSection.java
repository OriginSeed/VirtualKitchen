package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiQueueProperties;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPoolFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Threads &amp; queues: worker threads per task pool (resized in place), queue depth caps, and request timeout and
 * retries. Every value is read on each request, so changes apply to the next one; running work is never interrupted.
 */
@Component
@Order(20)
public class RuntimeConfigSection implements ConfigSection {

    /** Task pool name (see {@code TaskPoolConfig}) to its label. */
    private static final Map<String, String> POOLS = new LinkedHashMap<>();

    static {
        POOLS.put("ai-text", "AI text requests");
        POOLS.put("visualization", "Step image generation");
        POOLS.put("visualization-orchestrator", "Image job coordinators");
        POOLS.put("narration", "Narration");
        POOLS.put("flow-generation-orchestrator", "Process generation coordinators");
        POOLS.put("global-asset", "Global asset images");
    }

    private static final List<String> THREADS = Fields.values(1, 2, 3, 4, 6, 8, 12, 16);
    private static final List<String> DEPTHS = Fields.values(5, 10, 20, 30, 50, 100);
    private static final List<String> TEXT_TIMEOUTS = Fields.values(30000, 60000, 120000, 250000, 300000, 450000, 600000);
    private static final List<String> RETRIES = Fields.values(0, 1, 2, 3, 4, 5);
    private static final List<String> BACKOFFS = Fields.values(250, 500, 1000, 2000, 5000);

    static final String TEXT_TIMEOUT = "request.timeout.TEXT_TO_TEXT";
    static final String MAX_RETRIES = "request.maxRetries";
    static final String RETRY_BACKOFF = "request.retryBackoffMs";
    static final String TEXT_DEPTH = "queue.aiTextMaxDepth";
    static final String IMAGE_DEPTH = "queue.aiImageMaxDepth";
    static final String TTS_DEPTH = "queue.aiTtsMaxDepth";

    private final TaskPoolFactory poolFactory;
    private final AiQueueProperties queueProperties;
    private final AiRequestProperties requestProperties;

    private final int baseTextDepth;
    private final int baseImageDepth;
    private final int baseTtsDepth;
    private final Long baseTextTimeout;
    private final int baseMaxRetries;
    private final long baseRetryBackoff;
    /** Pool sizes as configured; captured on first use, since the pools are created by their own beans. */
    private Map<String, Integer> basePoolThreads;

    public RuntimeConfigSection(TaskPoolFactory poolFactory, AiQueueProperties queueProperties,
                                AiRequestProperties requestProperties) {
        this.poolFactory = poolFactory;
        this.queueProperties = queueProperties;
        this.requestProperties = requestProperties;
        this.baseTextDepth = queueProperties.getAiTextMaxDepth();
        this.baseImageDepth = queueProperties.getAiImageMaxDepth();
        this.baseTtsDepth = queueProperties.getAiTtsMaxDepth();
        this.baseTextTimeout = requestProperties.getTimeoutMs().get(AiCapability.TEXT_TO_TEXT.name());
        this.baseMaxRetries = requestProperties.getMaxRetries();
        this.baseRetryBackoff = requestProperties.getRetryBackoffMs();
    }

    @Override
    public String id() {
        return "runtime";
    }

    @Override
    public String label() {
        return "Threads & Queues";
    }

    @Override
    public String description() {
        return "Worker threads per pool, how many requests may wait in each queue, and how long and how often a "
                + "request is tried. Changes apply to the next request; running work finishes as it is.";
    }

    @Override
    public List<ConfigField> fields(Map<String, String> overrides) {
        List<ConfigField> fields = new ArrayList<>();
        basePoolThreads().forEach((pool, threads) -> fields.add(Fields.select(poolKey(pool), POOLS.get(pool),
                "Worker threads", THREADS, threads, overrides, "Threads in the '" + pool + "' pool.")));

        fields.add(Fields.select(TEXT_DEPTH, "Text queue", "Queue depth caps", DEPTHS, baseTextDepth, overrides,
                "Text requests that may wait; more are refused as busy."));
        fields.add(Fields.select(IMAGE_DEPTH, "Image queue", "Queue depth caps", DEPTHS, baseImageDepth, overrides,
                "Image requests that may wait."));
        fields.add(Fields.select(TTS_DEPTH, "Narration queue", "Queue depth caps", DEPTHS, baseTtsDepth, overrides,
                "Narration requests that may wait."));

        fields.add(Fields.select(TEXT_TIMEOUT, "Text request timeout (ms)", "Requests", TEXT_TIMEOUTS,
                baseTextTimeout == null ? 30000L : baseTextTimeout, overrides,
                "How long a text request may take before it fails."));
        fields.add(Fields.select(MAX_RETRIES, "Retries after a failure", "Requests", RETRIES, baseMaxRetries,
                overrides, "Extra attempts for a failed request (rate limits are retried separately)."));
        fields.add(Fields.select(RETRY_BACKOFF, "Wait between retries (ms)", "Requests", BACKOFFS, baseRetryBackoff,
                overrides, null));
        return fields;
    }

    @Override
    public void apply(Map<String, String> overrides) {
        Fields.requireKnown(overrides, fields(overrides));
        // Parse everything before changing anything, so a bad value leaves the live config untouched.
        Map<String, Integer> threads = new LinkedHashMap<>();
        basePoolThreads().forEach((pool, base) -> threads.put(pool, Fields.intValue(overrides, poolKey(pool), base)));
        int textDepth = Fields.intValue(overrides, TEXT_DEPTH, baseTextDepth);
        int imageDepth = Fields.intValue(overrides, IMAGE_DEPTH, baseImageDepth);
        int ttsDepth = Fields.intValue(overrides, TTS_DEPTH, baseTtsDepth);
        Long textTimeout = overrides.containsKey(TEXT_TIMEOUT) ? Long.valueOf(overrides.get(TEXT_TIMEOUT)) : baseTextTimeout;
        int maxRetries = Fields.intValue(overrides, MAX_RETRIES, baseMaxRetries);
        long backoff = Fields.longValue(overrides, RETRY_BACKOFF, baseRetryBackoff);

        threads.forEach((pool, n) -> poolFactory.find(pool).ifPresent(p -> {
            if (p.nThreads() != n) {
                p.resize(n);
            }
        }));
        queueProperties.setAiTextMaxDepth(textDepth);
        queueProperties.setAiImageMaxDepth(imageDepth);
        queueProperties.setAiTtsMaxDepth(ttsDepth);
        Map<String, Long> timeouts = new LinkedHashMap<>(requestProperties.getTimeoutMs());
        if (textTimeout == null) {
            timeouts.remove(AiCapability.TEXT_TO_TEXT.name());
        } else {
            timeouts.put(AiCapability.TEXT_TO_TEXT.name(), textTimeout);
        }
        requestProperties.setTimeoutMs(timeouts);
        requestProperties.setMaxRetries(maxRetries);
        requestProperties.setRetryBackoffMs(backoff);
    }

    private synchronized Map<String, Integer> basePoolThreads() {
        if (basePoolThreads == null) {
            Map<String, Integer> sizes = new LinkedHashMap<>();
            for (String pool : POOLS.keySet()) {
                poolFactory.find(pool).map(TaskPool::nThreads).ifPresent(n -> sizes.put(pool, n));
            }
            basePoolThreads = sizes;
        }
        return basePoolThreads;
    }

    private static String poolKey(String pool) {
        return "pool." + pool + ".threads";
    }
}
