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
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.none())
                .type(typeMatcher)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(createFieldWriteHook()))
                .type(ElementMatchers.named("java.lang.reflect.Field"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ReflectiveSetAdvice.class).on(
                                ElementMatchers.named("set")
                                        .or(ElementMatchers.named("setBoolean"))
                                        .or(ElementMatchers.named("setByte"))
                                        .or(ElementMatchers.named("setChar"))
                                        .or(ElementMatchers.named("setShort"))
                                        .or(ElementMatchers.named("setInt"))
                                        .or(ElementMatchers.named("setLong"))
                                        .or(ElementMatchers.named("setFloat"))
                                        .or(ElementMatchers.named("setDouble"))
                        )))
                .installOn(inst);
        try {
            inst.retransformClasses(java.lang.reflect.Field.class);
        } catch (Exception e) {
            System.out.println("Failed to retransform Field: " + e.getMessage());
        }
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
}
