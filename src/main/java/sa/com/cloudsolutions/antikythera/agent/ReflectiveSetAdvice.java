package sa.com.cloudsolutions.antikythera.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * ByteBuddy advice class that intercepts reflective field set operations to track field value changes.
 *
 * <p>When a field is set via reflection, this advice
 * captures the change and propagates it to the associated evaluation engine's symbol table.</p>
 *
 * <h2>Key Design Decisions</h2>
 * <ul>
 *   <li><b>Pure JDK Reflection:</b> Uses only standard Java reflection APIs to avoid class loader
 *       issues when instrumenting bootstrap classes like {@code java.lang.reflect.Field}.</li>
 *   <li><b>Recursion Prevention:</b> Employs a {@link ThreadLocal} flag to prevent infinite recursion
 *       when the advice's own reflective operations trigger further interceptions.</li>
 *   <li><b>Fail-Safe:</b> All exceptions are caught and suppressed to ensure the advice never breaks
 *       the application's normal behavior.</li>
 *   <li><b>Instance Fields Only:</b> Only tracks instance field modifications; static fields are
 *       excluded as they don't have an associated {@code instanceInterceptor}.</li>
 * </ul>
 *
 * <h2>Integration with Antikythera</h2>
 * <p>This advice integrates with the Antikythera evaluation framework by:</p>
 * <ol>
 *   <li>Locating the {@code instanceInterceptor} field on the target object</li>
 *   <li>Extracting the {@code evaluator} (EvaluationEngine) from the interceptor</li>
 *   <li>Retrieving the Symbol corresponding to the modified field</li>
 *   <li>Updating the Symbol's value to reflect the reflective change</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>The advice is thread-safe through the use of {@link ThreadLocal} for recursion tracking.
 * Each thread maintains its own recursion state.</p>
 *
 * @see net.bytebuddy.asm.Advice
 * @see java.lang.reflect.Field#set(Object, Object)
 */
@SuppressWarnings("java:S3011")
public class ReflectiveSetAdvice {

    /**
     * ThreadLocal flag to track if we're currently inside an agent-originated call.
     *
     * <p>This flag prevents infinite recursion that could occur when the advice itself
     * uses reflection to update the symbol table, which might trigger this same advice again.
     * The flag is set to {@code true} when entering the advice logic and reset to {@code false}
     * when exiting.</p>
     *
     * <p><b>Visibility:</b> Must be {@code public} to be accessible from {@code java.lang.reflect.Field},
     * which resides in the bootstrap module and has restricted visibility rules.</p>
     *
     * <p><b>Thread Safety:</b> Each thread has its own independent copy of this flag, preventing
     * cross-thread interference.</p>
     */
    public static final ThreadLocal<Boolean> IN_AGENT_CALL = ThreadLocal.withInitial(() -> false);

    /**
     * Advice method that executes after a reflective field set operation completes.
     *
     * <p>This method is woven into {@link Field#set(Object, Object)} and related methods.
     * It intercepts successful field modifications and propagates the changes to the
     * Antikythera evaluation engine's symbol table.</p>
     *
     * <h3>Execution Flow</h3>
     * <ol>
     *   <li><b>Guard Checks:</b> Validates preconditions and prevents recursion</li>
     *   <li><b>Locate Interceptor:</b> Searches the class hierarchy for {@code instanceInterceptor}</li>
     *   <li><b>Extract Evaluator:</b> Retrieves the {@code evaluator} field from the interceptor</li>
     *   <li><b>Find Symbol:</b> Calls {@code evaluator.getField(fieldName)} to get the Symbol</li>
     *   <li><b>Update Value:</b> Calls {@code symbol.setValue(value)} to sync the change</li>
     * </ol>
     *
     * <h3>Exclusion Criteria</h3>
     * <p>The advice exits early without processing if:</p>
     * <ul>
     *   <li>Already in an agent call (recursion prevention)</li>
     *   <li>The field set operation threw an exception</li>
     *   <li>The target object is {@code null} (static field case)</li>
     *   <li>The field is {@code instanceInterceptor} itself (avoid self-tracking)</li>
     * </ul>
     *
     * <h3>Reflection Strategy</h3>
     * <p>The method uses a multi-layered reflection approach:</p>
     * <ul>
     *   <li><b>Field Access:</b> Searches up the class hierarchy using {@code getDeclaredField()}</li>
     *   <li><b>Method Access:</b> Tries {@code getMethod()} first, falls back to {@code getDeclaredMethod()}</li>
     *   <li><b>Accessibility:</b> Calls {@code setAccessible(true)} to bypass access restrictions</li>
     * </ul>
     *
     * <h3>Error Handling</h3>
     * <p>All exceptions are caught and ignored. This ensures that even if the advice logic fails
     * (e.g., missing fields, incompatible types), the original reflective operation completes
     * successfully and the application continues normally.</p>
     *
     * @param self The {@link Field} object being used to set the value (the {@code this} reference)
     * @param target The object instance whose field is being modified; {@code null} for static fields
     * @param value The new value being assigned to the field; type is dynamically determined
     * @param thrown Any exception thrown by the original field set operation; {@code null} if successful
     *
     * @see Advice.OnMethodExit
     * @see Advice.This
     * @see Advice.Argument
     * @see Advice.Thrown
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(
            @Advice.This Field self,
            @Advice.Argument(0) Object target,
            @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value,
            @Advice.Thrown Throwable thrown
    ) {
        // ========== Guard Checks ==========

        // Prevent recursive interception from agent-originated calls.
        // If we're already processing an agent call, exit immediately to avoid infinite loops.
        if (IN_AGENT_CALL.get()) return;

        // Only process successful field sets; if an exception was thrown, skip processing.
        if (thrown != null) return;

        // Static fields don't have an instance and therefore no instanceInterceptor.
        // Skip them as we can't track their changes in the per-instance symbol table.
        if (target == null) return;

        // Avoid tracking the instanceInterceptor field itself to prevent circular references
        // and unnecessary overhead.
        if (self == null || self.getName().equals("instanceInterceptor") ) return;

        // ========== Enter Agent Context ==========

        // Mark that we're entering agent code. Any reflective operations we perform
        // will see this flag and avoid re-triggering this advice.
        IN_AGENT_CALL.set(true);
        try {
            // ========== Step 1: Locate the instanceInterceptor field ==========

            // Search up the class hierarchy to find the 'instanceInterceptor' field.
            // This field is injected by ByteBuddy into instrumented classes and holds
            // the MethodInterceptor that manages the evaluation state.
            Class<?> t = target.getClass();
            Field interceptorField = null;
            while (t != null && t != Object.class) {
                try {
                    interceptorField = t.getDeclaredField("instanceInterceptor");
                    break; // Found it, stop searching
                } catch (NoSuchFieldException e) {
                    // Not in this class, try the superclass
                    t = t.getSuperclass();
                }
            }

            // If we found the interceptor field, proceed to extract the evaluator
            if (interceptorField != null) {
                interceptorField.setAccessible(true);
                Object methodInterceptor = interceptorField.get(target);

                if (methodInterceptor != null) {
                    // ========== Step 2: Extract the evaluator from the MethodInterceptor ==========

                    // The MethodInterceptor contains an 'evaluator' field that references
                    // the EvaluationEngine responsible for managing the symbol table.
                    Field evaluatorField = null;
                    Class<?> miClass = methodInterceptor.getClass();
                    while (miClass != null && miClass != Object.class) {
                        try {
                            evaluatorField = miClass.getDeclaredField("evaluator");
                            break;
                        } catch (NoSuchFieldException e) {
                            // Not in this class, try the superclass
                            miClass = miClass.getSuperclass();
                        }
                    }

                    if (evaluatorField != null) {
                        evaluatorField.setAccessible(true);
                        Object evaluator = evaluatorField.get(methodInterceptor);

                        if (evaluator != null) {
                            // ========== Step 3: Get the Symbol for this field ==========

                            // Call evaluator.getField(fieldName) to retrieve the Symbol object
                            // that represents this field in the evaluation engine's symbol table.
                            Method getFieldMethod = null;
                            try {
                                // Try public method first
                                getFieldMethod = evaluator.getClass().getMethod("getField", String.class);
                            } catch (NoSuchMethodException e) {
                                // Fall back to declared method (possibly private/protected)
                                getFieldMethod = evaluator.getClass().getDeclaredMethod("getField", String.class);
                                getFieldMethod.setAccessible(true);
                            }

                            Object symbol = getFieldMethod.invoke(evaluator, self.getName());

                            if (symbol != null) {
                                // ========== Step 4: Update the Symbol's value ==========

                                // Call symbol.setValue(value) to synchronize the symbol table
                                // with the reflectively-modified field value.
                                Method setValueMethod = null;
                                try {
                                    // Try public method first
                                    setValueMethod = symbol.getClass().getMethod("setValue", Object.class);
                                } catch (NoSuchMethodException e) {
                                    // Fall back to declared method (possibly private/protected)
                                    setValueMethod = symbol.getClass().getDeclaredMethod("setValue", Object.class);
                                    setValueMethod.setAccessible(true);
                                }

                                // Update the symbol with the new value
                                setValueMethod.invoke(symbol, value);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // ========== Fail-Safe Error Handling ==========

            // Catch all exceptions to ensure the advice never breaks application behavior.
            // Possible exceptions include:
            // - NoSuchFieldException: Field doesn't exist in the class hierarchy
            // - NoSuchMethodException: Method doesn't exist (API mismatch)
            // - IllegalAccessException: Access denied despite setAccessible
            // - InvocationTargetException: Target method threw an exception
            // - ClassCastException: Type mismatch
            // - NullPointerException: Unexpected null reference
            //
            // All are silently ignored - tracking is best-effort, not critical.
        } finally {
            // ========== Exit Agent Context ==========

            // Always clear the flag when exiting, even if an exception occurred.
            // This ensures the thread can process future reflective operations normally.
            IN_AGENT_CALL.set(false);
        }
    }
}
