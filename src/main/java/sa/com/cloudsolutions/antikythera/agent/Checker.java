package sa.com.cloudsolutions.antikythera.agent;

import java.lang.reflect.Field;

/**
 * Utility called from injected bytecode to perform runtime checks and logging.
 */
public final class Checker {

    private Checker() {}

    /**
     * Called on field access or assignment.
     * @param instance the object whose field is being accessed or modified (null for static fields)
     * @param owner internal JVM name of the field's owner class (e.g., java/lang/String)
     * @param name field name
     * @param desc field descriptor (e.g., Ljava/lang/String;)
     * @param isWrite true if a write (PUTFIELD), false if a read (GETFIELD)
     */
    public static void check(Object instance, String owner, String name, String desc, boolean isWrite) {
        try {
            if (instance == null) {
                // We are not handling static fields for this agent as per requirement (instance-level interceptor)
                return;
            }
            // Avoid self-instrumentation loops if the agent inspects its own classes
            if (instance.getClass().getName().startsWith("sa.com.cloudsolutions.antikythera.agent")) {
                return;
            }

            String action = isWrite ? "WRITE" : "READ";
            String ownerClass = owner.replace('/', '.');
            System.out.println("[AntikytheraAgent] " + action + " " + ownerClass + "." + name + " desc=" + desc +
                    " on instance of " + instance.getClass().getName());

            Field interceptorField = findField(instance.getClass(), "methodInterceptor");
            if (interceptorField == null) {
                System.out.println("[AntikytheraAgent] methodInterceptor field NOT found on instance " + instance.getClass().getName());
                return;
            }
            interceptorField.setAccessible(true);
            Object interceptor = interceptorField.get(instance);
            if (interceptor == null) {
                System.out.println("[AntikytheraAgent] methodInterceptor is null on instance " + instance.getClass().getName());
                return;
            }

            Field evaluatorField = findField(interceptor.getClass(), "evaluator");
            if (evaluatorField == null) {
                System.out.println("[AntikytheraAgent] methodInterceptor present, but evaluator field NOT found on type " + interceptor.getClass().getName());
                return;
            }
            evaluatorField.setAccessible(true);
            Object evaluator = evaluatorField.get(interceptor);
            if (evaluator != null) {
                System.out.println("[AntikytheraAgent] evaluator is NOT null on methodInterceptor (type=" + interceptor.getClass().getName() + ")");
            } else {
                System.out.println("[AntikytheraAgent] evaluator is null on methodInterceptor (type=" + interceptor.getClass().getName() + ")");
            }
        } catch (Throwable t) {
            // Swallow to avoid interfering with target app. Log minimal info.
            System.out.println("[AntikytheraAgent] Checker error: " + t);
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> cls = type;
        while (cls != null) {
            try {
                return cls.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
