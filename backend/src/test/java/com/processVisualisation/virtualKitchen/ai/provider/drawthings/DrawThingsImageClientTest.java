package com.processVisualisation.virtualKitchen.ai.provider.drawthings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ImageGenerationSettings;
import com.processVisualisation.virtualKitchen.ai.provider.config.DrawThingsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Draw Things takes width, height and an optional step count; settings replace the configured size. */
class DrawThingsImageClientTest {

    private static final String BASE_URL = "http://drawthings.example";
    private static final String URL = BASE_URL + "/sdapi/v1/txt2img";
    private static final String RESPONSE = "{\"images\":[\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2}) + "\"]}";

    private DrawThingsProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new DrawThingsProperties();
        properties.setEndpoint("/sdapi/v1/txt2img");
        properties.setWidth(512);
        properties.setHeight(512);
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void settingsSizeAndStepsAreSent() {
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.prompt").value("chop onions"))
                .andExpect(jsonPath("$.width").value(768))
                .andExpect(jsonPath("$.height").value(640))
                .andExpect(jsonPath("$.steps").value(8))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        GeneratedImage image = client().generate("chop onions", List.of(), new ImageGenerationSettings(768, 640, 8));

        server.verify();
        assertEquals(768, image.usage().imageWidth());
        assertEquals(640, image.usage().imageHeight());
    }

    @Test
    void withoutSettings_theConfiguredSizeIsSentAndNoSteps() {
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.width").value(512))
                .andExpect(jsonPath("$.height").value(512))
                .andExpect(jsonPath("$.steps").doesNotExist())
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.width").value(512))
                .andExpect(jsonPath("$.steps").doesNotExist())
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client().generate("chop onions");
        client().generate("chop onions", List.of(), ImageGenerationSettings.NONE);

        server.verify();
    }

    @Test
    void partialSettingsKeepTheConfiguredRest() {
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.width").value(512))
                .andExpect(jsonPath("$.height").value(512))
                .andExpect(jsonPath("$.steps").value(4))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client().generate("chop onions", List.of(), new ImageGenerationSettings(null, null, 4));
        server.verify();
    }

    private DrawThingsImageClient client() {
        return new DrawThingsImageClient(builder.build(), properties, new ObjectMapper());
    }
}
