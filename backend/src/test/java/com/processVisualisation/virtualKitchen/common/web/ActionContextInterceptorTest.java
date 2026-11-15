package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionContextInterceptorTest {

    private static final String BASE = "com.processVisualisation.virtualKitchen";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void namesTheActionFromTheControllerAndMethodWhenNotAnnotated() throws Exception {
        var name = describe("kitchen.controller.KitchenController", "update");

        assertThat(name.area()).isEqualTo("kitchen");
        assertThat(name.action()).isEqualTo("kitchen.update");
    }

    @Test
    void classLevelLogActionReplacesTheControllerNoun() throws Exception {
        var name = describe("recipe.controller.RecipeTemplateController", "update");

        assertThat(name.area()).isEqualTo("recipe");
        assertThat(name.action()).isEqualTo("recipe.update");
    }

    @Test
    void derivesFeatureAreasFromThePackage() throws Exception {
        assertThat(describe("ai.recipe.narration.controller.StepNarrationController", "ensureAll"))
                .isEqualTo(new ActionContextInterceptor.ActionName("narration", "narration.ensureAll"));
        assertThat(describe("ai.recipe.assetimport.RecipeAssetImportController", "importAssets").area()).isEqualTo("recipe");
        assertThat(describe("recipeorder.controller.RecipeOrderController", "create").area()).isEqualTo("order");
        assertThat(describe("ai.globalasset.controller.AdminGlobalAssetController", "generate").area()).isEqualTo("asset");
        assertThat(describe("ai.chat.controller.AiChatController", "chat").area()).isEqualTo("ai");
    }

    @Test
    void everyControllerEndpointGetsAnAreaAndAWellFormedAction() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> problems = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();
        int handlers = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                handlers++;
                var name = ActionContextInterceptor.describe(type, method);
                String where = type.getSimpleName() + "." + method.getName();
                if ("other".equals(name.area())) {
                    problems.add(where + " has no area");
                }
                if (!name.action().matches("^[a-z][A-Za-z0-9_]*\\.[a-z][A-Za-z0-9_.]*$")) {
                    problems.add(where + " -> " + name.action());
                }
                String previous = seen.put(name.action(), where);
                if (previous != null) {
                    problems.add(name.action() + " names both " + previous + " and " + where);
                }
            }
        }
        assertThat(handlers).isGreaterThan(50);
        assertThat(problems).isEmpty();
    }

    @Test
    void putsTheNamesAndOnlyWhitelistedIdPathVariablesInTheMdc() throws Exception {
        Class<?> type = Class.forName(BASE + ".recipe.controller.RecipeTemplateController");
        Method update = handler(type, "update");
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/recipes/63");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(
                "recipeId", "63",
                "stepId", "bad\nINFO forged line",
                "email", "someone@example.com"));

        new ActionContextInterceptor().preHandle(request, new MockHttpServletResponse(), new HandlerMethod(stub(type), update));

        assertThat(MDC.get(MdcKeys.ACTION)).isEqualTo("recipe.update");
        assertThat(MDC.get(MdcKeys.AREA)).isEqualTo("recipe");
        assertThat(MDC.get(MdcKeys.RECIPE_ID)).isEqualTo("63");
        assertThat(MDC.get(MdcKeys.STEP_ID)).as("unsafe characters are dropped").isNull();
        assertThat(MDC.getCopyOfContextMap().values()).as("other path variables never reach the logs")
                .doesNotContain("someone@example.com");
    }

    private static ActionContextInterceptor.ActionName describe(String relativeClass, String method) throws Exception {
        Class<?> type = Class.forName(BASE + "." + relativeClass);
        return ActionContextInterceptor.describe(type, handler(type, method));
    }

    private static Method handler(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                return method;
            }
        }
        throw new IllegalArgumentException(type.getSimpleName() + " has no handler " + name);
    }

    /** HandlerMethod needs an instance; the interceptor only reads its type and method. */
    private static Object stub(Class<?> type) {
        return org.mockito.Mockito.mock(type);
    }
}
