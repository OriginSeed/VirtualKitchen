package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Names the user action each request serves, so every log line written while handling it, and every background job
 * it starts, carries {@code area=} and {@code action=}:
 * <ul>
 *   <li>{@code area}: {@link LogAction#area()} if given, otherwise derived from the controller's package
 *       ({@code recipe}, {@code order}, {@code narration}, ...);</li>
 *   <li>{@code action}: {@link LogAction#value()} if given, otherwise {@code <controllerNoun>.<method>}, e.g.
 *       {@code recipeTemplate.update};</li>
 *   <li>a whitelist of id path variables ({@code recipeId}, {@code processId}, ...) is copied too. No other path
 *       variable is: some carry personal data ({@code /users/email/{email}}).</li>
 * </ul>
 * The keys need no cleanup here: {@code RequestIdFilter} clears the whole MDC when the request finishes.
 */
public class ActionContextInterceptor implements HandlerInterceptor {

    private static final String BASE_PACKAGE = "com.processVisualisation.virtualKitchen.";

    /** Path variables safe to log, mapped to their MDC key. */
    private static final Map<String, String> ID_PATH_VARIABLES = Map.of(
            "recipeId", MdcKeys.RECIPE_ID,
            "processId", MdcKeys.PROCESS_ID,
            "workflowId", MdcKeys.WORKFLOW_ID,
            "orderId", MdcKeys.ORDER_ID,
            "stepId", MdcKeys.STEP_ID);

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9:_.-]{1,80}$");

    private final Map<Method, ActionName> names = new ConcurrentHashMap<>();

    /** Area and action for one handler method. */
    public record ActionName(String area, String action) {
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            ActionName name = names.computeIfAbsent(handlerMethod.getMethod(), m -> describe(handlerMethod));
            MDC.put(MdcKeys.AREA, name.area());
            MDC.put(MdcKeys.ACTION, name.action());
            copyIdPathVariables(request);
        }
        return true;
    }

    private static void copyIdPathVariables(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables)) {
            return;
        }
        ID_PATH_VARIABLES.forEach((variable, mdcKey) -> {
            if (variables.get(variable) instanceof String value && SAFE_ID.matcher(value).matches()) {
                MDC.put(mdcKey, value);
            }
        });
    }

    /** The area and action names for a handler: annotation first, then derived from the class and method. */
    public static ActionName describe(HandlerMethod handlerMethod) {
        return describe(handlerMethod.getBeanType(), handlerMethod.getMethod());
    }

    /** The area and action names for {@code method} of controller {@code type}. */
    public static ActionName describe(Class<?> type, Method method) {
        LogAction onMethod = AnnotatedElementUtils.findMergedAnnotation(method, LogAction.class);
        LogAction onType = AnnotatedElementUtils.findMergedAnnotation(type, LogAction.class);

        String area = firstNonBlank(onMethod == null ? null : onMethod.area(),
                onType == null ? null : onType.area(), areaOf(type));
        String action = onMethod != null && !onMethod.value().isBlank()
                ? onMethod.value()
                : (onType != null && !onType.value().isBlank() ? onType.value() : controllerNoun(type))
                  + "." + method.getName();
        return new ActionName(area, action);
    }

    /** Feature area from the package: {@code ai.recipe.narration...} to {@code narration}, {@code recipeorder} to {@code order}. */
    static String areaOf(Class<?> type) {
        String name = type.getName();
        if (!name.startsWith(BASE_PACKAGE)) {
            return "other";
        }
        String[] parts = name.substring(BASE_PACKAGE.length()).split("\\.");
        String top = parts[0];
        if ("ai".equals(top)) {
            if (parts.length > 3 && "recipe".equals(parts[1])) {
                return "assetimport".equals(parts[2]) ? "recipe" : parts[2];
            }
            return "globalasset".equals(parts.length > 2 ? parts[1] : "") ? "asset" : "ai";
        }
        return "recipeorder".equals(top) ? "order" : top;
    }

    /** {@code RecipeTemplateController} to {@code recipeTemplate}. */
    static String controllerNoun(Class<?> type) {
        String simple = type.getSimpleName();
        int proxyMarker = simple.indexOf("$$");
        if (proxyMarker > 0) {
            simple = simple.substring(0, proxyMarker);
        }
        if (simple.endsWith("Controller") && simple.length() > "Controller".length()) {
            simple = simple.substring(0, simple.length() - "Controller".length());
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "other";
    }
}
