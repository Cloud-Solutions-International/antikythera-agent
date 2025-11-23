package sa.com.cloudsolutions.antikythera.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Java agent that intercepts reflective field writes via java.lang.reflect.Field#set*(Object, ...)
 * and updates the corresponding Symbol in the EvaluationEngine if the target object has an
 * instanceInterceptor field.
 *
 * The agent can be installed via premain/agentmain or programmatically by calling initialize().
 */
public class AntikytheraAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    /**
     * Allows runtime installation without -javaagent by attaching a Byte Buddy agent to the current JVM
     * and then registering our transformers on the returned Instrumentation.
     */
    public static void initialize() {
        Instrumentation inst = ByteBuddyAgent.install();
        install(inst);
    }

    private static void install(Instrumentation inst) {
        try {
            // Manually append ReflectiveSetAdvice to bootstrap classloader
            ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(AntikytheraAgent.class.getClassLoader());
            byte[] adviceClass = locator.locate(ReflectiveSetAdvice.class.getName()).resolve();

            // Create a temporary JAR file containing the advice class
            java.io.File tempJar = java.io.File.createTempFile("antikythera-agent-advice", ".jar");
            tempJar.deleteOnExit();

            try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                    new java.io.FileOutputStream(tempJar))) {
                java.util.jar.JarEntry entry = new java.util.jar.JarEntry(
                        ReflectiveSetAdvice.class.getName().replace('.', '/') + ".class");
                jos.putNextEntry(entry);
                jos.write(adviceClass);
                jos.closeEntry();
            }

            // Add to bootstrap classloader
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tempJar));

        } catch (Exception e) {
            throw new RuntimeException("Failed to inject ReflectiveSetAdvice into bootstrap classloader", e);
        }

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.none())
                // Only instrument java.lang.reflect.Field for reflective field sets
                .type(ElementMatchers.named("java.lang.reflect.Field"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ReflectiveSetAdvice.class).on(
                                ElementMatchers.named("set")
                                        .or(ElementMatchers.named("setBoolean"))
                                        .or(ElementMatchers.named("setByte"))
                                        .or(ElementMatchers.named("setChar"))
                                        .or(ElementMatchers.named("setShort"))
                                        .or(ElementMatchers.named("setInt"))
                                        .or(ElementMatchers.named("setLong"))
                                        .or(ElementMatchers.named("setFloat"))
                                        .or(ElementMatchers.named("setDouble"))
                        )))
                .installOn(inst);
        try {
            inst.retransformClasses(java.lang.reflect.Field.class);
        } catch (Exception e) {
            System.out.println("Failed to retransform Field: " + e.getMessage());
        }
    }
}
