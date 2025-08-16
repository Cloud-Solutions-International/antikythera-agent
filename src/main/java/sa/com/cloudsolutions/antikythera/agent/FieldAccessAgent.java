package sa.com.cloudsolutions.antikythera.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Main entry point for the Java Agent. This agent intercepts both direct
 * and reflective field assignments to log access.
 */
public class FieldAccessAgent {

    /**
     * The premain method, which is the entry point for the Java Agent,
     * called by the JVM at startup.
     *
     * @param agentArgs       Agent arguments passed from the command line.
     * @param instrumentation The instrumentation interface provided by the JVM.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("[FieldAccessAgent] Initializing agent...");

        new AgentBuilder.Default()
                // Rule 1: Instrument java.lang.reflect.Field to catch reflective access.
                .type(named("java.lang.reflect.Field"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(named("set")) // Target the 'set' method
                                .intercept(Advice.to(ReflectiveFieldAccessAdvice.class))
                )
                // Rule 2: Instrument all other classes to catch direct field access, but with exclusions.
                .type(
                        // Start with the basic exclusions
                        not(isInterface()).and(not(nameStartsWith("net.bytebuddy.")))
                                // AND the class must NOT be in any of the specified packages.
                                .and(not(
                                        nameStartsWith("sa.com.cloudsolutions.antikythera.evaluator")
                                                .or(nameStartsWith("sa.com.cloudsolutions.antikythera.depsolver"))
                                                .or(nameStartsWith("sa.com.cloudsolutions.antikythera.generator"))
                                                .or(nameStartsWith("sa.com.cloudsolutions.antikythera.parser"))
                                                .or(nameStartsWith("sa.com.cloudsolutions.antikythera.configuration"))
                                ))
                )
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(DirectFieldAccessAdvice.class)
                                .on(isSetter().and(not(isSynthetic()))))
                )
                // Add a listener to see which classes are being transformed (optional, but useful for debugging)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                        System.out.println("[FieldAccessAgent] Transformed: " + typeDescription.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                        System.err.println("[FieldAccessAgent] Error transforming " + typeName + ": " + throwable.getMessage());
                    }
                })
                .installOn(instrumentation);

        System.out.println("[FieldAccessAgent] Agent installed successfully.");
    }

    /**
     * Advice class for intercepting direct field assignments (e.g., person.name = "value").
     * This advice is applied to any method that acts as a setter.
     */
    public static class DirectFieldAccessAdvice {
        @Advice.OnMethodExit
        public static void logDirectAccess(
                @Advice.This Object instance,
                @Advice.Argument(0) Object value,
                @Advice.Origin("#f") String fieldName
        ) {
            System.out.printf("[AGENT LOG - DIRECT] Field '%s' on object [%s] was assigned the value: '%s'%n",
                    fieldName,
                    instance.getClass().getName(),
                    value
            );
        }
    }

    /**
     * Advice class for intercepting reflective field assignments (e.g., field.set(obj, "value")).
     * This advice is applied to the `java.lang.reflect.Field.set` method.
     */
    public static class ReflectiveFieldAccessAdvice {

        /**
         * This advice is now skipped if the condition in ExcludedPackageCondition returns true.
         * This is much more performant than an if-statement inside the method body.
         */
        @Advice.OnMethodEnter(skipOn = ExcludedPackageCondition.class)
        public static void logReflectiveAccess(
                @Advice.This Field field,
                @Advice.Argument(0) Object instance,
                @Advice.Argument(1) Object value
        ) {
            System.out.printf("[AGENT LOG - REFLECTION] Field '%s' on object [%s] was assigned the value: '%s'%n",
                    field.getName(),
                    instance.getClass().getName(),
                    value
            );
        }
    }

    /**
     * A condition class used by Byte Buddy's Advice. If the onEnter method
     * returns true, the advice body is skipped entirely.
     */
    public static class ExcludedPackageCondition {
        /**
         * @param instance The instance being modified (passed from the advice's arguments).
         * @return true if the instance belongs to an excluded package, causing the advice to be skipped.
         */
        public static boolean onEnter(@Advice.Argument(0) Object instance) {
            String className = instance.getClass().getName();
            return className.startsWith("sa.com.cloudsolutions.antikythera.evaluator") ||
                    className.startsWith("sa.com.cloudsolutions.antikythera.depsolver") ||
                    className.startsWith("sa.com.cloudsolutions.antikythera.generator") ||
                    className.startsWith("sa.com.cloudsolutions.antikythera.parser") ||
                    className.startsWith("sa.com.cloudsolutions.antikythera.configuration");
        }
    }
}
