package sa.com.cloudsolutions.antikythera.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Utility class used by injected bytecode. Keeps dependencies minimal and uses reflection so
 * we don't need compile-time access to EvaluationEngine or Symbol types.
 */
public final class Support {
    private Support() {}

    public static void afterSet(Object self, String name, Object value) {
        if (self == null) return;
        try {
            Field f = findField(self.getClass(), "instanceInterceptor");
            if (f == null) return;
            f.setAccessible(true);
            Object interceptor = f.get(self);
            if (interceptor == null) return;

            // Try to resolve the setField(String, Symbol) method reflectively.
            Method setField = findSetFieldMethod(interceptor.getClass());
            if (setField == null) return;
            setField.setAccessible(true);
            // We pass null for the Symbol value to avoid compile-time dependency.
            setField.invoke(interceptor, name, null);
        } catch (Throwable ignored) {
            // Swallow any exception to avoid disrupting the application flow.
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> t = type;
        while (t != null && t != Object.class) {
            try {
                return t.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                t = t.getSuperclass();
            }
        }
        return null;
    }

    private static Method findSetFieldMethod(Class<?> type) {
        // Try exact signature first if EvaluationEngine is on classpath
        try {
            Class<?> symbolClass = Class.forName("sa.com.cloudsolutions.antikythera.evaluator.Symbol");
            return type.getMethod("setField", String.class, symbolClass);
        } catch (Throwable ignored) {
            // Fall back to any method named setField(String, Object-like)
            for (Method m : type.getMethods()) {
                if (!m.getName().equals("setField")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == String.class) {
                    // Accept any reference type for parameter 2
                    if (!p[1].isPrimitive()) {
                        return m;
                    }
                }
            }
        }
        return null;
    }
}
