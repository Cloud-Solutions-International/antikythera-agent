package sa.com.cloudsolutions.antikythera.agent;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import java.util.Map;

// Minimal mock Symbol implementation
class MockSymbol implements sa.com.cloudsolutions.antikythera.evaluator.Symbol {
    private Object value;
    private String name;
    @Override public void setValue(Object value) { this.value = value; }
    @Override public Object getValue() { return value; }
    @Override public void setName(String name) { this.name = name; }
    @Override public String getName() { return name; }
    @Override public Type getType() { return null; }
    @Override public void setType(Type type) {}
}

// Minimal mock EvaluationEngine implementation
class MockEvaluationEngine implements sa.com.cloudsolutions.antikythera.evaluator.EvaluationEngine {
    public MockSymbol symbol = new MockSymbol();
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol getField(String name) { symbol.setName(name); return symbol; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol getValue(Node n, String name) { return null; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol evaluateExpression(Expression expr) { return null; }
    @Override public void setField(String nameAsString, sa.com.cloudsolutions.antikythera.evaluator.Symbol v) {}
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol getLocal(Node node, String name) { return null; }
    @Override public Map<Integer, Map<String, sa.com.cloudsolutions.antikythera.evaluator.Symbol>> getLocals() { return null; }
    @Override public void visit(MethodDeclaration md) {}
    @Override public String getClassName() { return null; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol executeMethod(CallableDeclaration<?> cd) { return null; }
    @Override public sa.com.cloudsolutions.antikythera.evaluator.Symbol executeMethod(MethodDeclaration md) { return null; }
    @Override public void executeConstructor(CallableDeclaration<?> md) {}
    @Override public void executeConstructor(ConstructorDeclaration cd) {}
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
