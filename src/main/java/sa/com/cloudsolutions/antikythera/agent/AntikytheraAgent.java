package sa.com.cloudsolutions.antikythera.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Java agent that installs a Byte Buddy transformation to classes that declare a field named
 * "instanceInterceptor". For such classes, after any successful field write (PUTFIELD/PUTSTATIC)
 * inside an instance method, we invoke Support.afterSet(this, fieldName, null).
 *
 * Additionally, we intercept reflective writes via java.lang.reflect.Field#set*(Object, ...)
 * and perform the same callback after successful completion.
 *
 * The agent can be installed via premain/agentmain or programmatically by calling initialize().
 */
public class AntikytheraAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    /**
     * Allows runtime installation without -javaagent by attaching a Byte Buddy agent to the current JVM
     * and then registering our transformers on the returned Instrumentation.
     */
    public static void initialize() {
        Instrumentation inst = ByteBuddyAgent.install();
        install(inst);
    }

    private static void install(Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> typeMatcher =
                ElementMatchers.declaresField(ElementMatchers.named("instanceInterceptor"));

        new AgentBuilder.Default()
                .ignore(ElementMatchers.none()) // we restrict by the transformation matcher instead
                // Instrument application classes that have an instanceInterceptor field
                .type(typeMatcher)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(createFieldWriteHook()))
                // Also instrument reflective sets performed through java.lang.reflect.Field
                .type(ElementMatchers.named("java.lang.reflect.Field"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ReflectiveSetAdvice.class).on(
                                ElementMatchers.nameStartsWith("set")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(0, Object.class))
                        )))
                .installOn(inst);
    }

    private static AsmVisitorWrapper createFieldWriteHook() {
        return new net.bytebuddy.asm.AsmVisitorWrapper.AbstractBase() {
            @Override
            public ClassVisitor wrap(TypeDescription instrumentedType,
                                     ClassVisitor classVisitor,
                                     Implementation.Context implementationContext,
                                     TypePool typePool,
                                     FieldList<FieldDescription.InDefinedShape> fields,
                                     MethodList<?> methods,
                                     int writerFlags,
                                     int readerFlags) {
                return new ClassVisitor(Opcodes.ASM9, classVisitor) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                                super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
                                if (!isStatic && (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitLdcInsn(fieldName);
                                    super.visitInsn(Opcodes.ACONST_NULL);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            "sa/com/cloudsolutions/antikythera/agent/Support",
                                            "afterSet",
                                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V",
                                            false
                                    );
                                }
                            }
                        };
                    }
                };
            }
        };
    }

    /**
     * Advice that runs after reflective field set operations. It uses only JDK reflection
     * to avoid class loader issues when instrumenting a bootstrap class.
     */
    public static class ReflectiveSetAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void after(
                @Advice.This Field self,
                @Advice.Argument(0) Object target,
                @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value,
                @Advice.Thrown Throwable thrown
        ) {
            System.out.println("AFTER");

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
}
