package sa.com.cloudsolutions.antikythera.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent entry point. Registers a transformer which instruments field reads and writes.
 */
public class AntikytheraAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[AntikytheraAgent] Initializing agent with args: " + agentArgs);
        FieldAccessTransformer transformer = new FieldAccessTransformer();
        inst.addTransformer(transformer, true);
        System.out.println("[AntikytheraAgent] Transformer registered.");
    }
}
