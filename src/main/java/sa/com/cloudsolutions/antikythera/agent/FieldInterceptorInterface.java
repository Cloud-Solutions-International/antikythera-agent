package sa.com.cloudsolutions.antikythera.agent;

public interface FieldInterceptorInterface {
    /**
     * Called immediately after a field assignment occurs on the owning object.
     * @param fieldName The name of the field whose value was just set.
     * @param newValue The value that was assigned to the field.
     */
    void setField(String fieldName, Object newValue);
}
