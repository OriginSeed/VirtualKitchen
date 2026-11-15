package com.processVisualisation.virtualKitchen.common.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the user action a controller endpoint serves, for the {@code action} / {@code area} MDC keys.
 * <p>
 * Optional: without it {@code ActionContextInterceptor} derives the area from the controller's package and the action
 * from {@code <controllerNoun>.<method>}. Use it where that derived name would be unclear, e.g.
 * {@code @LogAction("recipe.publish")} on a method. On a class, {@link #value} replaces the controller noun for every
 * handler that does not override it: {@code @LogAction("recipe")} on {@code RecipeTemplateController} names its
 * {@code update} handler {@code recipe.update}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LogAction {

    /** On a method: the full action name. On a class: the noun before {@code .<method>}. Empty: derive it. */
    String value() default "";

    /** Feature area, e.g. {@code recipe}. Empty: derive from the package. */
    String area() default "";
}
