package sa.com.cloudsolutions.antikythera.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bytecode.member.MemberSubstitution;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class AntikytheraAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[AntikytheraAgent] Initializing agent with args: " + agentArgs);

        new AgentBuilder.Default()
                .with(new AgentBuilder.Listener.StreamWriting(System.err).withErrorsOnly())
                // Ignore classes from Byte Buddy, the agent itself, and system libraries
                .ignore(nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("sa.com.cloudsolutions.antikythera.agent."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun.")))
                .type(not(isInterface()).and(not(isEnum()))) // Don't instrument interfaces or enums
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(MemberSubstitution.relaxed()
                                .field(not(isStatic())) // Target instance fields for reads
                                .onRead()
                                .replaceWith(MethodDelegation.to(FieldInterceptor.class)))
                        .visit(MemberSubstitution.relaxed()
                                .field(not(isStatic())) // Target instance fields for writes
                                .onWrite()
                                .replaceWith(MethodDelegation.to(FieldInterceptor.class)))
                ).installOn(inst);

        System.out.println("[AntikytheraAgent] Byte Buddy Transformer registered.");
    }
}
