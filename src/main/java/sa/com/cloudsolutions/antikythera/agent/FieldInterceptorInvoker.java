package sa.com.cloudsolutions.antikythera.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FieldInterceptorInvoker {
    private FieldInterceptorInvoker() {}

    /**
     * Notify the interceptor attached to the actual owning object of the modified field.
     * This method will reflectively locate a non-static field named "instanceInterceptor"
     * in the owner's class hierarchy, and if present invoke its setField(String,Object).
     * Any error conditions are swallowed to avoid impacting application behavior.
     */
    public static void notifyOwner(Object owner, String fieldName, Object newValue) {
        if (owner == null) return;
        try {
            // Find instanceInterceptor field in the class hierarchy
            Class<?> c = owner.getClass();
            Field interceptorField = null;
            while (c != null) {
                try {
                    interceptorField = c.getDeclaredField("instanceInterceptor");
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (interceptorField == null) return;
            if ((interceptorField.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) return;
            interceptorField.setAccessible(true);
            Object interceptor = interceptorField.get(owner);
            if (interceptor == null) return;

            Method m = interceptor.getClass().getMethod("setField", String.class, Object.class);
            m.invoke(interceptor, fieldName, newValue);
        } catch (Throwable ignored) {
            // Be silent; we must not break application flow.
        }
    }
}
