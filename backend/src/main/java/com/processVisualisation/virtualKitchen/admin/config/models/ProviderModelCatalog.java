package com.processVisualisation.virtualKitchen.admin.config.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.GeminiProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Lists the models a provider offers, for the admin "add model" form's model id dropdown. Read-only GET requests
 * through each provider's existing {@link RestClient} and API key; results carry model ids only (never a key) and
 * are cached for {@link #TTL}. A provider without a list endpoint, or one that can't be reached, says so, and the
 * admin types the id instead.
 */
@Component
public class ProviderModelCatalog {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_MODELS = 1000;

    private final RestClient deepInfra;
    private final RestClient gemini;
    private final RestClient openAi;
    private final RestClient ollama;
    private final DeepInfraProperties deepInfraProperties;
    private final GeminiProperties geminiProperties;
    private final OpenAIProperties openAiProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public ProviderModelCatalog(@Qualifier("deepInfraRestClient") RestClient deepInfra,
                                @Qualifier("geminiRestClient") RestClient gemini,
                                @Qualifier("openAiRestClient") RestClient openAi,
                                @Qualifier("ollamaRestClient") RestClient ollama,
                                DeepInfraProperties deepInfraProperties, GeminiProperties geminiProperties,
                                OpenAIProperties openAiProperties, ObjectMapper objectMapper) {
        this(deepInfra, gemini, openAi, ollama, deepInfraProperties, geminiProperties, openAiProperties, objectMapper,
                Clock.systemUTC());
    }

    ProviderModelCatalog(RestClient deepInfra, RestClient gemini, RestClient openAi, RestClient ollama,
                         DeepInfraProperties deepInfraProperties, GeminiProperties geminiProperties,
                         OpenAIProperties openAiProperties, ObjectMapper objectMapper, Clock clock) {
        this.deepInfra = deepInfra;
        this.gemini = gemini;
        this.openAi = openAi;
        this.ollama = ollama;
        this.deepInfraProperties = deepInfraProperties;
        this.geminiProperties = geminiProperties;
        this.openAiProperties = openAiProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The models {@code providerBean} offers for {@code capability}, or why they can't be listed. */
    public Listing list(String providerBean, AiCapability capability) {
        String cacheKey = providerBean + "|" + capability;
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.at().plus(TTL).isAfter(clock.instant())) {
            return cached.listing();
        }
        Listing listing = fetch(providerBean, capability);
        if (listing.error() == null) {
            cache.put(cacheKey, new Cached(listing, clock.instant()));
        }
        return listing;
    }

    private Listing fetch(String providerBean, AiCapability capability) {
        String provider = providerOf(providerBean);
        try {
            return switch (provider) {
                case "deepinfra" -> deepInfra(capability);
                case "gemini" -> gemini(capability);
                case "openai" -> openAi(capability);
                case "ollama" -> ollama();
                default -> Listing.unsupported("This provider has no model list; type the model id.");
            };
        } catch (RestClientResponseException e) {
            return Listing.failed("The provider answered " + e.getStatusCode().value() + "; type the model id instead.");
        } catch (RestClientException e) {
            return Listing.failed("The provider can't be reached; type the model id instead.");
        } catch (Exception e) {
            return Listing.failed("The provider's model list couldn't be read; type the model id instead.");
        }
    }

    /**
     * DeepInfra's native list carries each model's type ({@code text-generation}, {@code text-to-image}, …); the
     * OpenAI-compatible list (no types) is the fallback.
     */
    private Listing deepInfra(AiCapability capability) throws Exception {
        if (!StringUtils.hasText(deepInfraProperties.getApiKey())) {
            return Listing.failed("No DeepInfra API key is configured.");
        }
        String bearer = "Bearer " + deepInfraProperties.getApiKey();
        try {
            JsonNode root = get(deepInfra.get().uri("/models/list").header(HttpHeaders.AUTHORIZATION, bearer));
            JsonNode items = root.isArray() ? root : root.path("data");
            List<Model> models = new ArrayList<>();
            for (JsonNode item : items) {
                String id = text(item, "model_name", "id");
                String type = text(item, "type", "reported_type");
                if (id != null && (type == null || deepInfraType(type) == capability)) {
                    models.add(new Model(id, id));
                }
            }
            if (!models.isEmpty()) {
                return Listing.of(models);
            }
        } catch (RestClientResponseException e) {
            // Fall through to the OpenAI-compatible list.
        }
        JsonNode root = get(deepInfra.get().uri("/v1/openai/models").header(HttpHeaders.AUTHORIZATION, bearer));
        return Listing.of(ids(root.path("data"), "id", id -> true));
    }

    private Listing gemini(AiCapability capability) throws Exception {
        if (!StringUtils.hasText(geminiProperties.getApiKey())) {
            return Listing.failed("No Gemini API key is configured.");
        }
        JsonNode root = get(gemini.get().uri("/v1beta/models?pageSize=1000").header("x-goog-api-key", geminiProperties.getApiKey()));
        List<Model> models = new ArrayList<>();
        for (JsonNode item : root.path("models")) {
            String name = text(item, "name");
            if (name == null) continue;
            String id = name.startsWith("models/") ? name.substring("models/".length()) : name;
            boolean generates = false;
            for (JsonNode method : item.path("supportedGenerationMethods")) {
                generates |= "generateContent".equals(method.asText());
            }
            if (generates && geminiFits(id, capability)) {
                String display = text(item, "displayName");
                models.add(new Model(id, display != null ? display + " (" + id + ")" : id));
            }
        }
        return Listing.of(models);
    }

    private Listing openAi(AiCapability capability) throws Exception {
        if (!StringUtils.hasText(openAiProperties.getApiKey())) {
            return Listing.failed("No OpenAI API key is configured.");
        }
        JsonNode root = get(openAi.get().uri("/v1/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiProperties.getApiKey()));
        Predicate<String> fits = switch (capability) {
            case TEXT_TO_TEXT -> id -> id.startsWith("gpt") || id.matches("o\\d.*");
            case TEXT_TO_IMAGE -> id -> id.startsWith("dall-e") || id.startsWith("gpt-image");
            case TEXT_TO_SPEECH -> id -> id.startsWith("tts") || id.contains("-tts");
        };
        return Listing.of(ids(root.path("data"), "id", fits));
    }

    private Listing ollama() throws Exception {
        JsonNode root = get(ollama.get().uri("/api/tags"));
        return Listing.of(ids(root.path("models"), "name", id -> true));
    }

    private JsonNode get(RestClient.RequestHeadersSpec<?> request) throws Exception {
        String body = request.retrieve().body(String.class);
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    private static List<Model> ids(JsonNode items, String field, Predicate<String> fits) {
        List<Model> models = new ArrayList<>();
        for (JsonNode item : items) {
            String id = text(item, field);
            if (id != null && fits.test(id)) {
                models.add(new Model(id, id));
            }
        }
        return models;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    static AiCapability deepInfraType(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        if (t.contains("image")) return AiCapability.TEXT_TO_IMAGE;
        if (t.contains("speech") || t.contains("tts")) return AiCapability.TEXT_TO_SPEECH;
        if (t.contains("text-generation") || t.contains("chat") || t.equals("text")) return AiCapability.TEXT_TO_TEXT;
        return null;
    }

    private static boolean geminiFits(String id, AiCapability capability) {
        boolean image = id.contains("image");
        boolean tts = id.contains("tts");
        return switch (capability) {
            case TEXT_TO_TEXT -> !image && !tts;
            case TEXT_TO_IMAGE -> image;
            case TEXT_TO_SPEECH -> tts;
        };
    }

    /** "deepInfraAiClient" to "deepinfra", "geminiImageClient" to "gemini", "openAiAiClient" to "openai". */
    static String providerOf(String providerBean) {
        String bean = providerBean == null ? "" : providerBean.toLowerCase(Locale.ROOT);
        for (String provider : List.of("deepinfra", "gemini", "openai", "ollama")) {
            if (bean.startsWith(provider)) return provider;
        }
        return bean;
    }

    public record Model(String id, String label) {
    }

    /**
     * @param supported false when the provider has no list endpoint at all
     * @param error     why the list couldn't be loaded, or null
     */
    public record Listing(List<Model> models, boolean supported, String error) {

        static Listing of(List<Model> models) {
            List<Model> sorted = models.stream().distinct().sorted(Comparator.comparing(Model::id))
                    .limit(MAX_MODELS).toList();
            return new Listing(sorted, true, null);
        }

        static Listing unsupported(String message) {
            return new Listing(List.of(), false, message);
        }

        static Listing failed(String message) {
            return new Listing(List.of(), true, message);
        }
    }

    private record Cached(Listing listing, Instant at) {
    }
}
