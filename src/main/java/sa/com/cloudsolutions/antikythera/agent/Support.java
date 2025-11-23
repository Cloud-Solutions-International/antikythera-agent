package sa.com.cloudsolutions.antikythera.agent;

/**
 * Support class for agent callbacks.
 */
public class Support {

    /**
     * Callback invoked after a field write operation on classes with instanceInterceptor.
     * This is called from bytecode-instrumented field writes in classes that have an instanceInterceptor field.
     *
     * @param instance the object whose field was written
     * @param fieldName the name of the field that was written
     * @param value the value that was written (may be null)
     */
    public static void afterSet(Object instance, String fieldName, Object value) {
        // Currently a placeholder. This method is invoked by bytecode injected into
        // classes with instanceInterceptor fields after any PUTFIELD/PUTSTATIC instruction.
        // Future functionality can be added here if needed.
    }
}
