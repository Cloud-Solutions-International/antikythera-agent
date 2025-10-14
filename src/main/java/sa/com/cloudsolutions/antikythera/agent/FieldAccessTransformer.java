package sa.com.cloudsolutions.antikythera.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Instruments classes that declare an instance field named "instanceInterceptor".
 * After any direct PUTFIELD to an instance field (except to the interceptor itself),
 * invokes interceptor.setField(fieldName, newValue) with the assigned value.
 */
public class FieldAccessTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            TransformingClassVisitor tcv = new TransformingClassVisitor(cw);
            cr.accept(tcv, ClassReader.EXPAND_FRAMES);
            if (tcv.isTargetClass()) {
                return cw.toByteArray();
            }
            return null; // no change
        } catch (Throwable t) {
            return null;
        }
    }

    static class TransformingClassVisitor extends ClassVisitor {
        private String className;
        private boolean isInterface;
        private boolean isTarget;
        private String interceptorFieldDesc; // descriptor of instanceInterceptor

        TransformingClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        boolean isTargetClass() {
            return isTarget;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            this.isInterface = (access & ACC_INTERFACE) != 0;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if ((access & ACC_STATIC) == 0 && "instanceInterceptor".equals(name)) {
                this.interceptorFieldDesc = descriptor;
                this.isTarget = !isInterface; // only if class, not interface
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!isInterface && interceptorFieldDesc != null && (access & ACC_STATIC) == 0) { // only instance methods
                return new TransformingMethodVisitor(api, mv, access, name, descriptor, className, interceptorFieldDesc);
            }
            return mv;
        }
    }

    static class TransformingMethodVisitor extends AdviceAdapter {
        private final String className;
        private final String interceptorFieldDesc;

        protected TransformingMethodVisitor(int api, MethodVisitor mv, int access, String name, String descriptor,
                                            String className, String interceptorFieldDesc) {
            super(api, mv, access, name, descriptor);
            this.className = className;
            this.interceptorFieldDesc = interceptorFieldDesc;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            System.out.println("Visiting field instruction: " + opcode + " " + owner + "." + name + " " + descriptor);
            if (opcode == PUTFIELD && !"instanceInterceptor".equals(name)) {
                // Stack before: ..., objectref, value
                // Save value and objectref into locals, perform original PUTFIELD, then notify using the actual owner object.
                Type valueType = Type.getType(descriptor);

                // Local for value
                int valLocal = newLocal(valueType);
                // store value (pops it), leaving objectref on stack
                switch (valueType.getSort()) {
                    case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> visitVarInsn(ISTORE, valLocal);
                    case Type.LONG -> visitVarInsn(LSTORE, valLocal);
                    case Type.FLOAT -> visitVarInsn(FSTORE, valLocal);
                    case Type.DOUBLE -> visitVarInsn(DSTORE, valLocal);
                    case Type.ARRAY, Type.OBJECT -> visitVarInsn(ASTORE, valLocal);
                    default -> visitVarInsn(ASTORE, valLocal);
                }

                // Local for objectref
                int objLocal = newLocal(Type.getType(Object.class));
                visitVarInsn(ASTORE, objLocal); // stores objectref

                // Reload for original PUTFIELD: first objectref, then value
                visitVarInsn(ALOAD, objLocal);
                switch (valueType.getSort()) {
                    case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> visitVarInsn(ILOAD, valLocal);
                    case Type.LONG -> visitVarInsn(LLOAD, valLocal);
                    case Type.FLOAT -> visitVarInsn(FLOAD, valLocal);
                    case Type.DOUBLE -> visitVarInsn(DLOAD, valLocal);
                    case Type.ARRAY, Type.OBJECT -> visitVarInsn(ALOAD, valLocal);
                    default -> visitVarInsn(ALOAD, valLocal);
                }

                // Original instruction
                super.visitFieldInsn(opcode, owner, name, descriptor);

                // After PUTFIELD, call helper: FieldInterceptorInvoker.notifyOwner(objectref, fieldName, boxedValue)
                visitVarInsn(ALOAD, objLocal); // owner object
                visitLdcInsn(name);            // field name
                // load value and box it
                switch (valueType.getSort()) {
                    case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                        visitVarInsn(ILOAD, valLocal);
                        box(Type.INT_TYPE);
                    }
                    case Type.LONG -> {
                        visitVarInsn(LLOAD, valLocal);
                        box(Type.LONG_TYPE);
                    }
                    case Type.FLOAT -> {
                        visitVarInsn(FLOAD, valLocal);
                        box(Type.FLOAT_TYPE);
                    }
                    case Type.DOUBLE -> {
                        visitVarInsn(DLOAD, valLocal);
                        box(Type.DOUBLE_TYPE);
                    }
                    case Type.ARRAY, Type.OBJECT -> visitVarInsn(ALOAD, valLocal);
                    default -> visitVarInsn(ALOAD, valLocal);
                }

                // invoke helper
                super.visitMethodInsn(INVOKESTATIC,
                        "sa/com/cloudsolutions/antikythera/agent/FieldInterceptorInvoker",
                        "notifyOwner",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V",
                        false);
                return;
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
}
