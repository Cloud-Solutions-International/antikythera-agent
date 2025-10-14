package sa.com.cloudsolutions.antikythera.agent;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.time.Instant;

/**
 * Utility to attach the Antikythera agent to a running JVM.
 * Usage:
 *   java -cp <this-jar>:<tools.jar> sa.com.cloudsolutions.antikythera.agent.AgentAttacher <pid> <path-to-agent-jar> [agentArgs]
 * If <pid> is "self", the current process PID is used.
 */
public class AgentAttacher {
    private static void log(String msg) {
        System.err.println("[AgentAttacher] " + Instant.now() + " - " + msg);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: AgentAttacher <pid|self> <path-to-agent-jar> [agentArgs]");
            listJvms();
            System.exit(2);
        }
        String pid = args[0];
        if ("self".equalsIgnoreCase(pid)) {
            pid = String.valueOf(ProcessHandle.current().pid());
        }
        String agentPath = args[1];
        String agentArgs = args.length > 2 ? args[2] : "";

        File jar = new File(agentPath);
        if (!jar.isFile()) {
            log("Agent jar not found: " + jar.getAbsolutePath());
            System.exit(3);
        }

        try {
            log("Attaching to PID=" + pid + "; agent=" + jar.getAbsolutePath());
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                vm.loadAgent(jar.getAbsolutePath(), agentArgs);
                log("Agent loaded successfully.");
            } finally {
                vm.detach();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.err.println("\nTroubleshooting tips:\n" +
                    "- Ensure you're running with a JDK (tools.jar is available) not a JRE.\n" +
                    "- On Linux/macOS, you may need the same user to attach and target JVM.\n" +
                    "- In containers, enable ptrace/attach as needed (e.g., --cap-add SYS_PTRACE).\n" +
                    "- If target JVM started with -XX:+DisableAttachMechanism, attaching is blocked.\n");
            System.exit(1);
        }
    }

    private static void listJvms() {
        System.err.println("Running JVMs detected by Attach API:");
        try {
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                System.err.println("  PID=" + d.id() + "\t" + d.displayName());
            }
        } catch (Throwable t) {
            // ignore
        }
    }
}
