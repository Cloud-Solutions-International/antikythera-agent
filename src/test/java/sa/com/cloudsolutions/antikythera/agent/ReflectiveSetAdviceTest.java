package sa.com.cloudsolutions.antikythera.agent;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

// Mock Symbol implementation
class MockSymbol implements sa.com.cloudsolutions.antikythera.evaluator.Symbol {
    private Object value;
    private String name;
    @Override public void setValue(Object value) { this.value = value; }
    @Override public Object getValue() { return value; }
    @Override public void setName(String name) { this.name = name; }
    @Override public String getName() { return name; }
    // ...other methods not needed for this test...
    @Override public com.github.javaparser.ast.type.Type getType() { return null; }
    @Override public void setType(com.github.javaparser.ast.type.Type type) {}
}

// Mock EvaluationEngine implementation
class MockEvaluationEngine implements sa.com.cloudsolutions.antikythera.evaluator.EvaluationEngine {
    public MockSymbol symbol = new MockSymbol();
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol getField(String name) { symbol.setName(name); return symbol; }
    // ...other methods not needed for this test...
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol getValue(com.github.javaparser.ast.Node n, String name) { return null; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol evaluateExpression(com.github.javaparser.ast.expr.Expression expr) { return null; }
    @Override public void setField(String nameAsString, sa.com.cloudsolutions.antikythera.evaluator.Symbol v) {}
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol getLocal(com.github.javaparser.ast.Node node, String name) { return null; }
    @Override public java.util.Map<Integer, java.util.Map<String, sa.com.cloudsolutions.antikythera.evaluator.Symbol>> getLocals() { return null; }
    @Override public void visit(com.github.javaparser.ast.body.MethodDeclaration md) {}
    @Override public String getClassName() { return null; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol executeMethod(com.github.javaparser.ast.body.CallableDeclaration<?> cd) { return null; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol executeMethod(com.github.javaparser.ast.body.MethodDeclaration md) { return null; }
    @Override public void executeConstructor(com.github.javaparser.ast.body.CallableDeclaration<?> md) {}
    @Override public void executeConstructor(com.github.javaparser.ast.body.ConstructorDeclaration cd) {}
}

// Dummy MethodInterceptor with evaluator field
class DummyMethodInterceptor {
    public MockEvaluationEngine evaluator;
    public DummyMethodInterceptor(MockEvaluationEngine evaluator) { this.evaluator = evaluator; }
}

// Test class with a field and instanceInterceptor
class TestTarget {
    public int foo;
    public DummyMethodInterceptor instanceInterceptor;
}

public class ReflectiveSetAdviceTest {
    @Test
    public void testAfterSetsSymbolValue() throws Exception {
        TestTarget target = new TestTarget();
        MockEvaluationEngine eval = new MockEvaluationEngine();
        target.instanceInterceptor = new DummyMethodInterceptor(eval);
        Field field = TestTarget.class.getDeclaredField("foo");
        int newValue = 123;
        field.set(target, newValue);
        // Simulate advice call
        ReflectiveSetAdvice.after(field, target, newValue, null);
        // Assert that the symbol's value was set
        assertEquals(newValue, eval.symbol.getValue());
        assertEquals("foo", eval.symbol.getName());
    }
}

