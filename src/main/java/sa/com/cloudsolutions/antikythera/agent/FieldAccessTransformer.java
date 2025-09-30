package sa.com.cloudsolutions.antikythera.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.PUTFIELD;

/**
 * Instruments classes to intercept instance field reads (GETFIELD) and writes (PUTFIELD).
 */
public class FieldAccessTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // Skip bootstrap and system classes and our own agent package
        if (className == null) return null;
        if (className.startsWith("java/") || className.startsWith("jdk/") || className.startsWith("javax/")
                || className.startsWith("sun/") || className.startsWith("com/sun/")
                || className.startsWith("sa/com/cloudsolutions/antikythera/agent")) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            switch (opcode) {
                                case GETFIELD -> {
                                    // Stack before GETFIELD: ..., obj
                                    // We will duplicate obj into a local var, call Checker.check(obj,..., false), then reload obj and perform GETFIELD
                                    int objLocal = newLocal(Type.getObjectType("java/lang/Object"));
                                    // store obj into local
                                    dup();
                                    // top of stack: obj, obj -> store one copy
                                    storeLocal(objLocal);
                                    // Load for call
                                    loadLocal(objLocal);
                                    push(owner);
                                    push(name);
                                    push(descriptor);
                                    push(false);
                                    invokeStatic(Type.getType(Checker.class), new Method("check", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"));
                                    // Reload original obj for GETFIELD
                                    loadLocal(objLocal);
                                    super.visitFieldInsn(opcode, owner, name, descriptor);
                                }
                                case PUTFIELD -> {
                                    // Stack before PUTFIELD: ..., obj, value
                                    // We will spill into locals: value -> valLocal, obj -> objLocal, call, then reload obj, value, then PUTFIELD
                                    Type fieldType = Type.getType(descriptor);
                                    int objLocal = newLocal(Type.getObjectType("java/lang/Object"));
                                    int valLocal = newLocal(fieldType);
                                    // store value
                                    storeLocal(valLocal);
                                    // store obj
                                    storeLocal(objLocal);
                                    // call checker with obj
                                    loadLocal(objLocal);
                                    push(owner);
                                    push(name);
                                    push(descriptor);
                                    push(true);
                                    invokeStatic(Type.getType(Checker.class), new Method("check", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"));
                                    // reload for original instruction
                                    loadLocal(objLocal);
                                    loadLocal(valLocal);
                                    super.visitFieldInsn(opcode, owner, name, descriptor);
                                }
                                default -> super.visitFieldInsn(opcode, owner, name, descriptor);
                            }
                        }
                    };
                }
            };
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.out.println("[AntikytheraAgent] Transform error on " + className + ": " + t);
            return null; // in case of errors, don't transform
        }
    }
}
