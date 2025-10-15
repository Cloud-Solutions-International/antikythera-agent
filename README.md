# Antikythera Agent

A lightweight Java instrumentation agent (Java 21) that observes and propagates field write events.
This agent is primarily intended to be used with Cloud Solutions' Antikythera test generation framework.

The agent performs two complementary tasks:

1. Bytecode field write hook: For every loaded/retransformed application class that declares a field named `instanceInterceptor`, 
the agent injects a callback after each successful field write (`PUTFIELD` or `PUTSTATIC`) executed inside an *instance* method.
The injected callback is a static method call to `Support.afterSet(this, fieldName, null)`.
2. Reflective write hook: It instruments the JDK class `java.lang.reflect.Field` so that after any reflective write via `Field#set*` methods, 
3. a reflective propagation routine (`ReflectiveSetAdvice`) locates the owning object's `instanceInterceptor` and ultimately updates an evaluation 
4. symbol representing the written field.

## Table of Contents
- [When to Use](#when-to-use)
- [Concepts](#concepts)
- [How It Works](#how-it-works)
  - [Class Selection](#class-selection)
  - [ASM Injection Details](#asm-injection-details)
  - [Reflective Advice Flow](#reflective-advice-flow)
- [Runtime Attachment](#runtime-attachment)
- [Build & Install](#build--install)
- [Using the Agent](#using-the-agent)
  - [As a `-javaagent` Argument](#as-a--javaagent-argument)
  - [Programmatic / Late Attachment](#programmatic--late-attachment)
- [Integration Contract](#integration-contract)
- [Limitations & Edge Cases](#limitations--edge-cases)
- [Performance Considerations](#performance-considerations)
- [Security Considerations](#security-considerations)
- [Testing](#testing)
- [Extending the Agent](#extending-the-agent)
- [Troubleshooting](#troubleshooting)
- [Roadmap Ideas](#roadmap-ideas)

## When to Use
Use this agent if you need to:
- Maintain a symbolic/evaluated representation (e.g., for scripting, live coding, or debugging) that mirrors the *current* field state of objects.
- Capture ordinary bytecode field writes *and* reflective writes (`Field#set*`) without modifying application source.

## Concepts
| Term | Meaning |
|------|---------|
| `instanceInterceptor` | Marker field whose presence enrolls a class in instrumentation. Holds an object with an `evaluator` field. |
| Evaluator | Object exposing a `getField(String)` method returning a Symbol. |
| Symbol | Object exposing `setValue(Object)` and metadata (e.g., name). |
| Support.afterSet | Static hook (not included here) expected to apply higher-level logic after a field change. |

## How It Works
### Class Selection
`AntikytheraAgent` builds a Byte Buddy pipeline that:
- Matches any type declaring a field literally named `instanceInterceptor`.
- Applies an ASM visitor (`createFieldWriteHook`) to inject a post-write callback.
- Separately targets `java.lang.reflect.Field` and applies `ReflectiveSetAdvice` to all `set`, `setXxx` primitive variants.

### ASM Injection Details
For each instrumented method:
- Every encountered `PUTFIELD` or `PUTSTATIC` bytecode inside a non-static method triggers bytecode insertion *after* the original write.
- Inserted sequence (simplified):
  1. `ALOAD 0` (load `this`)
  2. `LDC <fieldName>`
  3. `ACONST_NULL` (placeholder value — downstream hook may re-read actual value if needed)
  4. `INVOKESTATIC Support.afterSet(Object, String, Object)`

Why `ACONST_NULL`? Avoids stack complexity and type-specific duplication; the real updated value can be reflectively resolved if the `Support` implementation requires it.

### Reflective Advice Flow
`ReflectiveSetAdvice.after(...)` runs on method exit of `Field#set*` if no exception occurred:
1. Ignore static target writes (since `target == null`).
2. Skip self-updates to the marker field (`instanceInterceptor`).
3. Traverse the target's class hierarchy to find `instanceInterceptor`.
4. From the interceptor object, locate its `evaluator` field.
5. Call `evaluator.getField(fieldName)` to obtain a Symbol.
6. Invoke `symbol.setValue(value)` with the just-written value.
7. Swallow all throwables to preserve application stability.

The advice deliberately uses *only* core reflection (no external library calls) to reduce risk when instrumenting a bootstrap class (`java.lang.reflect.Field`).

## Runtime Attachment
Two entry points are declared in the manifest (via shade & jar plugins):
- `Premain-Class` / `Agent-Class`: `sa.com.cloudsolutions.antikythera.agent.AntikytheraAgent`
- Capabilities: class redefinition & retransformation enabled (`Can-Redefine-Classes`, `Can-Retransform-Classes`).

You can either:
- Supply `-javaagent:/path/antikythera-agent-1.0-SNAPSHOT.jar` at JVM startup, or
- Call `AntikytheraAgent.initialize()` inside a running JVM (Byte Buddy will self-attach using the attach API; tools.jar not required on modern JDKs).

## Build & Install
Prerequisites: JDK 21, Maven 3.8+.

Build shaded agent JAR:
```
mvn clean package
```
Output of interest:
- `target/antikythera-agent-1.0-SNAPSHOT.jar` (shaded, manifest configured)

## Using the Agent
### As a `-javaagent` Argument
```
java -javaagent:./antikythera-agent-1.0-SNAPSHOT.jar -jar your-app.jar
```
All subsequently loaded matching classes will be instrumented. Already loaded matching classes may need either to be loaded 
after agent premain or require an explicit retransformation strategy (already enabled).

### Programmatic / Late Attachment
Embed (ensure the JAR is on the application classpath):
```java
public static void main(String[] args) {
    sa.com.cloudsolutions.antikythera.agent.AntikytheraAgent.initialize();
    // Continue with application logic
}
```
This triggers `ByteBuddyAgent.install()` internally, obtaining an `Instrumentation` instance and installing transformations, then retransforms `java.lang.reflect.Field`.

## Integration Contract
Your participating application classes must:
1. Declare a field named exactly `instanceInterceptor` (any visibility). Existence triggers instrumentation.
2. The `instanceInterceptor` object must expose (directly or via inheritance) a field named `evaluator`.
3. The `evaluator` object must provide a public or declared method `getField(String)` returning a Symbol-like object.
4. The returned Symbol must provide a method `setValue(Object)`.

Minimal sketch:
```java
class MySymbol { void setValue(Object v) { ... } }
class MyEvaluator { MySymbol getField(String name) { ... } }
class MyMethodInterceptor { MyEvaluator evaluator; }
class MyDomainObject { MyMethodInterceptor instanceInterceptor; int counter; }
```

## Limitations & Edge Cases
- Static field updates via reflection are ignored (no target instance, no `instanceInterceptor`).
- Bytecode hook only fires for writes executed inside *instance methods*; static methods are skipped (check uses `!isStatic`).
- Injected callback passes `null` as the value; if concrete new value is required, `Support.afterSet` must re-fetch it (reflection or VarHandle) using the provided object & field name.
- The `Support` class is referenced but not present in this module; it is expected to reside on the target application's classpath (likely in `antikythera-common`). Ensure its binary name: `sa.com.cloudsolutions.antikythera.agent.Support`.
- If a security manager (legacy) or restrictive module boundaries block `setAccessible(true)`, reflective advice may become a no-op.
- Multiple successive writes in one method body each trigger a callback.

## Performance Considerations
- Per-write overhead: one static method call (bytecode instrumentation) plus whatever `Support.afterSet` does.
- Reflective path adds hierarchy & field lookups; mitigated by early exits and minimal branching.
- No caching layer currently for field/method lookups. For high-frequency reflective writes, adding caches (e.g., `ConcurrentHashMap<Class<?>, Field>`) could help.

## Security Considerations
- Uses `setAccessible(true)` indiscriminately. In containerized or modularized environments, consider adding guards.
- Swallows all exceptions in advice, which preserves stability but can conceal integration misconfiguration; add logging in controlled environments when debugging.

## Testing
Current test suite (`ReflectiveSetAdviceTest`) performs a focused unit test on `ReflectiveSetAdvice.after(...)` by:
- Creating a mock evaluation engine & symbol.
- Simulating a reflective set of an `int` field.
- Asserting the symbol receives value & name.

To run tests:
```
mvn test
```

Potential missing tests:
- End-to-end integration validating bytecode injection by launching a test JVM with `-javaagent`.
- Static field write scenarios.
- Multiple hierarchy levels for `instanceInterceptor` and `evaluator` fields.

## Extending the Agent
Ideas:
- Capture previous value (read before write) to enable change-diff notifications.
- Include value type descriptors and generics metadata.
- Add configuration via agent arguments (e.g., opt-in class patterns, logging verbosity) using `premain(String agentArgs, ...)`.
- Provide caching for reflective lookups.
- Support static field symbol updates (derive symbol scope differently).

## Troubleshooting
| Symptom | Possible Cause | Remedy |
|---------|----------------|--------|
| No callbacks occur | Class lacks `instanceInterceptor` field or loaded before agent without retransformation | Verify field name; ensure agent premain or call `initialize()` early |
| `NoSuchMethodException: getField` in advice | Evaluator does not implement expected API | Implement or adapt evaluator contract |
| `IllegalAccessException` during advice | Module boundaries or security manager | Open packages or adjust JVM args (`--add-opens`) |
| Crash / linkage errors when instrumenting `java.lang.reflect.Field` | JDK version mismatch or unsupported changes | Verify JDK 21 compatibility; update Byte Buddy version |

## Roadmap Ideas
- Pluggable value propagation strategies.
- Granular opt-out annotations instead of global marker field.
- Structured event emission (e.g., to Flight Recorder or an event bus).
- Observability metrics counters for write frequency.

## License
(Add license information here if/when a license file is introduced.)

---
Generated README documenting the current implementation status (commit context not embedded). Update as the integration contract evolves.

