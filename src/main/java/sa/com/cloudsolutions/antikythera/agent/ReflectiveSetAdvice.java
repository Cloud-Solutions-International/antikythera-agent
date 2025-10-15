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
        if (self == null || self.getName().equals("instanceInterceptor") ) return;

        try {
            // Find 'instanceInterceptor' field up the hierarchy
            Class<?> t = target.getClass();
            Field interceptorField = null;
            while (t != null && t != Object.class) {
                try {
                    interceptorField = t.getDeclaredField("instanceInterceptor");
                    break;
                } catch (NoSuchFieldException e) {
                    t = t.getSuperclass();
                }
            }
            if (interceptorField != null) {
                interceptorField.setAccessible(true);
                Object methodInterceptor = interceptorField.get(target);
                if (methodInterceptor != null) {
                    // Get 'evaluator' field from MethodInterceptor
                    Field evaluatorField = null;
                    Class<?> miClass = methodInterceptor.getClass();
                    while (miClass != null && miClass != Object.class) {
                        try {
                            evaluatorField = miClass.getDeclaredField("evaluator");
                            break;
                        } catch (NoSuchFieldException e) {
                            miClass = miClass.getSuperclass();
                        }
                    }
                    if (evaluatorField != null) {
                        evaluatorField.setAccessible(true);
                        Object evaluator = evaluatorField.get(methodInterceptor);
                        if (evaluator != null) {
                            // Use reflection to call getField(String) on the evaluator (EvaluationEngine)
                            Method getFieldMethod = null;
                            try {
                                getFieldMethod = evaluator.getClass().getMethod("getField", String.class);
                            } catch (NoSuchMethodException e) {
                                getFieldMethod = evaluator.getClass().getDeclaredMethod("getField", String.class);
                                getFieldMethod.setAccessible(true);
                            }
                            Object symbol = getFieldMethod.invoke(evaluator, self.getName());
                            if (symbol != null) {
                                // Use reflection to call setValue(Object) on the Symbol
                                Method setValueMethod = null;
                                try {
                                    setValueMethod = symbol.getClass().getMethod("setValue", Object.class);
                                } catch (NoSuchMethodException e) {
                                    setValueMethod = symbol.getClass().getDeclaredMethod("setValue", Object.class);
                                    setValueMethod.setAccessible(true);
                                }
                                setValueMethod.invoke(symbol, value);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // never let reflective hook break application behavior
        }
    }
}
