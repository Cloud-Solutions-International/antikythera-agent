package sa.com.cloudsolutions.antikythera.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Advice that runs after reflective field set operations. It uses only JDK reflection
 * to avoid class loader issues when instrumenting a bootstrap class.
 */
public class ReflectiveSetAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(
            @Advice.This Field self,
            @Advice.Argument(0) Object target,
            @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value,
            @Advice.Thrown Throwable thrown
    ) {
        if (thrown != null) return; // only after successful sets
        if (target == null) return; // static fields not supported (no instanceInterceptor)
        try {
            // Find 'instanceInterceptor' field up the hierarchy
            Class<?> t = target.getClass();
            java.lang.reflect.Field interceptorField = null;
            while (t != null && t != Object.class) {
                try {
                    interceptorField = t.getDeclaredField("instanceInterceptor");
                    break;
                } catch (NoSuchFieldException e) {
                    t = t.getSuperclass();
                }
            }
            if (interceptorField == null) return;
            interceptorField.setAccessible(true);
            Object interceptor = interceptorField.get(target);
            if (interceptor == null) return;

            // Resolve setField(String, Symbol/any reference) method
            Method setField;
            try {
                Class<?> symbolClass = Class.forName("sa.com.cloudsolutions.antikythera.evaluator.Symbol");
                setField = interceptor.getClass().getMethod("setField", String.class, symbolClass);
            } catch (Throwable ignore) {
                setField = null;
                for (Method m : interceptor.getClass().getMethods()) {
                    if (!m.getName().equals("setField")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == String.class && !p[1].isPrimitive()) {
                        setField = m;
                        break;
                    }
                }
            }
            if (setField == null) return;
            setField.setAccessible(true);
            // We pass null for Symbol value to avoid dependency, consistent with direct write hook
            setField.invoke(interceptor, self.getName(), null);
        } catch (Throwable ignore) {
            // never let reflective hook break application behavior
        }
    }
}
