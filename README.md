# kotlinx-lincheck

[![JetBrains incubator project](https://jb.gg/badges/incubator.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Download](https://api.bintray.com/packages/kotlin/kotlinx/kotlinx.lincheck/images/download.svg) ](https://bintray.com/kotlin/kotlinx/kotlinx.lincheck/_latestVersion)
[![Build Status](https://travis-ci.org/Kotlin/kotlinx-lincheck.svg?branch=master)](https://travis-ci.org/Kotlin/kotlinx-lincheck)

**Lincheck** is a framework for testing concurrent data structures for correctness. In order to use the framework, operations to be executed concurrently should be specified with the necessary information for an execution scenario generation. With the help of this specification, **lincheck** generates different scenarios, executes them in concurrent environment several times and then checks that the execution results are correct (usually, linearizable, but different relaxed contracts can be used as well).

The artifacts are available in [Bintray](https://bintray.com/kotlin/kotlinx/kotlinx.lincheck) and JCenter. For Maven and Gradle use `org.jetbrains.kotlinx:lincheck:<version>` artifact in for your tests.

##### Given talks:
 *  [Lock-free algorithms testing](http://nkoval.info/talks/#lock_free_algorithms_testing) by Nikita Koval


This is a fork of [Lin-Check framework by Devexperts](https://github.com/Devexperts/lin-check); the last one is no longer being developed.


Table of contents
=================
   * [Test structure](#test-structure)
      * [Initial state](#initial-state)
      * [Operations and groups](#operations-and-groups)
         * [Calling at most once](#calling-at-most-once)
         * [Exception as a result](#exception-as-a-result)
         * [Operation groups](#operation-groups)
      * [Parameter generators](#parameter-generators)
         * [Binding parameter and generator names](#binding-parameter-and-generator-names)
      * [Sequential specification](#sequential-specification)
      * [Run test](#run-test)
   * [Execution strategies](#execution-strategies)
      * [Stress strategy](#stress-strategy)
   * [Correctness contracts](#correctness-contracts)
      * [Linearizability](#linearizability)
      * [Serializability](#serializability)
      * [Quiescent consistency](#quiescent-consistency)
   * [Blocking data structures](#blocking-data-structures)   
   * [Configuration via options](#configuration-via-options)
   * [Sample](#sample)
   * [Contacts](#contacts)





# Test structure
The first thing we need to do is to define operations to be executed concurrently. They are specified as `public` methods with an `@Operation` annotation in the test class. If an operation has parameters, generators for them have to be specified. The second step is to set an initial state in the empty constructor. After the operations and the initial state are specified, **lincheck** uses them for test scenarios generations and runs them.

## Initial state
In order to specify the initial state, the empty argument constructor is used. It is guaranteed that before every test invocation a new test class instance is created.

## Operations and groups
As described above, each operation is specified via `@Operation` annotation.

```java
@Operation
public Integer poll() { return q.poll(); }
```

### Calling at most once
If an operation should be called at most once during the test execution, you can set `@Operation(runOnce = true)` option and this operation appears at most one time in the generated scenario.

### Exception as a result
If an operation can throw an exception and this is a normal result (e.g. `remove` method in `Queue` implementation throws `NoSuchElementException` if the queue is empty), it can be handled as a result if `@Operation(handleExceptionsAsResult = ...)` options are specified. See the example below where `NoSuchElementException` is processed as a normal result.

```java
@Operation(handleExceptionsAsResult = NoSuchElementException.class)
public int remove() { return queue.remove(); }
```

### Operation groups
In order to support single producer/consumer patterns and similar ones, each operation could be included in an operation group. Then the operation group could have some restrictions, such as non-parallel execution.

In order to specify an operation group, `@OpGroupConfig` annotation should be added to the test class with the specified group name and its configuration:

* **nonParallel** - if set all operations from this group will be invoked from one thread.

Here is an example with single-producer multiple-consumer queue test:

```java
@OpGroupConfig(name = "producer", nonParallel = true)
public class SPMCQueueTest {
  private SPMCQueue<Integer> q = new SPMCQueue<>();
  
  @Operation(group = "producer")
  public void offer(Integer x) { q.offer(x); }
  
  @Operation
  public Integer poll() { return q.poll(); }
}
```

> A generator for `x` parameter is omitted and the default is used. See [Default generators](#default-generators) paragraph for details.

## Parameter generators
If an operation has parameters then generators should be specified for each of them. There are several ways to specify a parameter generator: explicitly on parameter via `@Param(gen = ..., conf = ...)` annotation, using named generator via `@Param(name = ...)` annotation, or using the default generator implicitly.

For setting a generator explicitly, `@Param` annotation with the specified class generator (`@Param(gen = ...)`) and string configuration (`@Param(conf = ...)`) should be used. The provided generator class should be a `ParameterGenerator` implementation and can be implemented by user. Out of the box **lincheck** supports random parameter generators for almost all primitives and strings. Note that only one generator class is used for both primitive and its wrapper, but boxing/unboxing does not happen. See `org.jetbrains.kotlinx.lincheck.paramgen` for details.

It is also possible to use once configured generators for several parameters. This requires adding this `@Param` annotation to the test class instead of the parameter specifying it's name (`@Param(name = ...)`). Then it is possible to use this generator among all operations using `@Param` annotation with the provided name only. It is also possible to bind parameter and generator names, see [Binding parameter and generator names](binding-parameter-and-generator-names) for details.

If the parameter generator is not specified **lincheck** tries to use the default one, binding supported primitive types with the existent generators and using the default configurations for them.

All standard generators support `distinct` configuration. Use it when you want to generate different values that will be used only once.
`@Param(name = ..., gen = ..., conf = "distinct")`.

### Binding parameter and generator names
Java 8 came with the feature ([JEP 188](http://openjdk.java.net/jeps/118)) to store parameter names to class files. If test class is compiled this way then they are used as the name of the already specified parameter generators.

For example, the two following code blocks are equivalent.

```java
@Operation
public Integer get(int key) { return map.get(key); }
```


```java
@Operation
public Integer get(@Param(name = "key") int key) {
  return map.get(key);
}
```

Unfortunately, this feature is disabled in **javac** compiler by default. Use `-parameters` option to enable it. In **Maven** you can use the following plugin configuration:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgument>-parameters</compilerArgument>
    </configuration>
</plugin>
```

> However, some IDEs (such as IntelliJ IDEA) do not understand build system configuration as well as possible and running a test from these IDEs will not work. In order to solve this issue you can add `-parameters` option for **javac** compiler in your IDE configuration.

## Sequential specification
By default, **lincheck** sequentially uses the testing data structure to define the correct specification.
However, it is sometimes better to define it explicitly, by writing a simple sequential implementation, and be sure that it is correct. 
Thus, **lincheck** can test that the testing data structure is correct even without parallelism.
This sequential specification class should have the same methods as the testing data structure.
The specification class can be defined via `sequentialSpecification` parameter in both `Options` instances and the corresponding annotations.

## Run test
In order to run a test, `LinChecker.check(...)` method should be executed with the provided test class as a parameter. Then **lincheck** looks at execution strategies to be used, which can be provided using annotations or options (see [Configuration via options](#configuration-via-options) for details), and runs a test with each of provided strategies. If an error is found, an `AssertionError` is thrown and the detailed error information is printed to the standard output. It is recommended to use **JUnit** or similar testing library to run `LinChecker.check(...)` method. 

```java
@StressCTest // stress execution strategy is used
public class MyConcurrentTest {
  <empty constructor and operations>
  
  @Test 
  public void runTest() { 
    LinChecker.check(MyConcurrentTest.class); 
  }
}
```

> It is possible to add several `@..CTest` annotations with different execution strategies or configurations and all of them should be processed. 





# Execution strategies
The section above describes how to specify the operations and the initial state, whereas this section is about executing the test. Using the provided operations **lincheck** generates several random scenarios and then executes them using the specified execution strategy. At this moment stress strategy is implemented only, but some managed strategies will be added soon as well.

## Stress strategy
The first implemented in **lincheck** execution strategy is stress testing strategy. This strategy uses the same idea as `JCStress` tool - it executes the generated scenario in parallel a lot of times in hope to hit on an interleaving which produces incorrect results. This strategy is pretty useful for finding bugs related to low-level effects (like a forgotten volatile modifier), but, unfortunately, does not guarantee any coverage. It is also recommended to use not only Intel processors with this strategy because its internal memory model is quite strong and cannot produce a lot of behaviors which are possible with ARM, for example. 

In order to use this strategy, just `@StressCTest` annotation should be added to the test class or `StressOptions` should be used if the test uses options to run (see [Configuration via options](#configuration-via-options) for details). Both of them are configured with the following options:

* **iterations** - number of different scenarios to be executed;
* **invocationsPerIteration** - number of invocations for each scenario;
* **threads** - number of threads to be used in a concurrent execution;
* **actorsPerThread** - number of operations to be executed in each thread;
* **actorsBefore** - number of operations to be executed before the concurrent part, sets up a random initial state;
* **actorsAfter** - number of operations to be executed after the concurrent part, helps to verify that a data structure is still correct;
* **verifier** - verifier for an expected correctness contract (see [Correctness contracts](#correctness-contracts) for details).





# Correctness contracts
Once the generated scenario is executed using the specified strategy, it is needed to verify the operation results for correctness. By default **lincheck** checks the result for linearizability, which is de-facto a standard type of correctness. However, there are also verifiers for some relaxed contracts, which should be set via `@..CTest(verifier = ..Verifier.class)` option.

## Linearizability
Linearizability is a de-facto standard correctness contract for thread-safe algorithms. Roughly, it says that an execution
is correct if there exists an equivalent sequential execution which produces the same results and does not violate 
the happens-before ordering of the initial one. By default, **lincheck** tests data structures for linearizability using `LinearizabilityVerifier`. 

Essentially, `LinearizabilityVerifier` lazily constructs a transition graph (aka LTS), where states are test instances and edges are operations. Using this transition graph, it tries to find a path which produces same results on operations and does not violate the happens-before order. 

### States equivalency

In order not to have state duplicates, equivalency relation between test instance states should be defined with `equals()` and `hashCode()` implementations. For that, a test class should extend `VerifierState` class and override `extractState()` function. `VerifierState` lazily gets the state of the test instance calling the function, caches the extracted state representation, and uses it for `equals()` and `hashCode()`.

### Test example

```java
@StressCTest(verifier = LinearizabilityVerifier.class)
public class ConcurrentQueueTest extends VerifierState {
  private Queue<Integer> q = new ConcurrentLinkedQueue<>();
  
  @Operation
  public Integer poll() {
    return q.poll();
  }
  
  @Operation
  public boolean offer(Integer val) {
    return q.offer(val);
  }
  
  @Override
  protected Object extractState() {
    return q;
  }
}
```

## Serializability
Serializability is one of the base contracts, which ensures that an execution is equivalent to the one that invokes operations in any serial order. The `SerializabilityVerifier` is used for this contract. 

Alike linearizability verification, it also constructs a transition graph 
and expects `extractState()` function override.

## Quiescent consistency
Quiescent consistency is a stronger guarantee than serializability but still relaxed comparing to linearizability. It ensures that an execution is equivalent to some operations sequence which produces the same results and does not reorder operation between quiescent points. Quiescent point is a cut where all operations before the cut are happens-before all operations after it. In order to check for this consistency, use `QuiescentConsistencyVerifier` and mark all quiescent consistent operations with `@QuiescentConsistent` annotation, all other operations are automatically linearizable. 

Alike linearizability verification, it also constructs a transition graph 
and expects `extractState()` function override.

### Test example

```java
@StressCTest(verifier = QuiescentConsistencyVerifier.class)
public class QuiescentQueueTest extends VerifierState {
  private QuiescentQueue<Integer> q = new QuiescentQueue<>();

  // Only this operation is quiescent consistent
  @Operation
  @QuiescentConsistent 
  public Integer poll() {
    return q.poll();
  }

  @Operation
  public boolean offer(Integer val) {
    return q.offer(val);
  }

  @Test
  public void test() {
    LinChecker.check(QuiescentQueueTest.class);
  }
  
  // extractState() here
}
```

# Blocking data structures

**Lincheck** supports blocking operations implemented with `suspend` functions from Kotlin language. The examples of such data structures from the Kotlin Coroutines library are mutexes, semaphores, and channels; see the [corresponding guide](https://kotlinlang.org/docs/reference/coroutines/coroutines-guide.html) to understand what these data structures are.

Most of such blocking algorithms can be formally described via the *dual data structures* formalism (see the paper below). In this formalism, each blocking operation is divided into two parts: the *request* (before suspension point) and the *follow-up* (after resumption); both these parts have their own linearization points, so they may be treated as separate operations within happens-before order. Splitting blocking operations into those parts allows to verify a dual data structure for contracts described above in the way similar to plain data structures. 

### Example with a rendezvous channel

For example, consider a [rendezvous channel](https://kotlinlang.org/docs/reference/coroutines/channels.html). 
There are two types of processes, producers and consumers, which perform `send` and `receive` operations respectively.
In order for a producer to `send` an element, it has to perform a rendezvous
exchange with a consumer, the last one gets the sent element.

```
class Channel<T> {
  suspend fun send(e: T)
  suspend fun receive(): T
}
```
  
Having the execution results below, where the first thread completes sending *42* to the channel, and the second one receives *42* from it, we have to construct a sequentional execution which produces the same results. 

```
    val c = Channel<Int>()
-----------------------------
c.send(42): void || c.receive(): 42
```

By splitting `receive` operation into two parts, we can construct a sequential execution as follows:

1. register `receive()`-s request into the internal waiting queue of the channel;
2. `send(42)` peforms a rendezvous with the already registered `receive()` and passes `42` to it;
3. the `receive()` resumes and returns *42*.

Similarly, we could split the `send(42)` operation into two parts.
 
### States equivalency

Equivalency relation among LTS states is defined by equivalency of the following properties: 
1. list of registered requests;
2. information about resumed operations;
3. externally observable state of the test instance.

**Lincheck** maintains both lists of registered requests and sets of resumed ones internally, while the externally observable state should be defined by the user.   

The externally observable state is defined in the same way as for plain data structures, with `equals()` and `hashCode()` implementations. 
For that, tests should extend `VerifierState` class and override `extractState()` function; 
the resulting state may include information of waiting requests as well, e.g. waiting `send` operation requests on channels.

In case of [buffered channels](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/index.html),
the externally observable state can be represented  with both elements from the buffer and waiting `send` operations as follows:

```kotlin
override fun extractState(): Any {
    val elements = mutableListOf<Int>()
    while (!ch.isEmpty) elements.add(ch.poll()!!)
    return elements
 }
```

### Test example
Here is an example of a test for buffered channels. All the suspendable operations should be marked with a `suspend` modifier.
 

```kotlin
@Param(name = "value", gen = IntGen::class, conf = "1:5")
@StressCTest(verifier = LinearizabilityVerifier::class, actorsAfter = 0)
class ChanelLinearizabilityTest : VerifierState() {
    val ch = Channel<Int>(capacity = 3) 

    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation
    suspend fun receive() = ch.receive()

    @Operation
    fun close() = ch.close()

    // state = elements in the channel + closed flag
    override fun extractState(): Any {
        val elements = mutableListOf<Int>()
        while (!ch.isEmpty) elements.add(ch.poll()!!)
        val closed = ch.isClosedForSend
        return elements to closed
    }
}
```

# Configuration via options
Instead of using `@..CTest` annotations for specifying the execution strategy and other parameters, it is possible to use `LinChecker.check(Class<?>, Options)` method and provide options for it. Every execution strategy has its own Options class (e.g., `StressOptions` for stress strategy) which should be used for it. See an example with stress strategy:

```java
public class MyConcurrentTest {
  <empty constructor and operations>
  
  @Test 
  public void runTest() { 
    Options opts = new StressOptions()
        .iterations(10)
        .threads(3)
        .logLevel(LoggingLevel.INFO);
    LinChecker.check(StressOptionsTest.class, opts);
  }
}
```


# Sample
Here is a test for a not thread-safe `HashMap` with its result. It uses the default configuration and tests `put` and `get` operations only:

**Test class**

```java
@Param(name = "key", gen = IntGen.class, conf = "1:5")
@StressCTest
public class HashMapLinearizabilityTest extends VerifierState {
    private HashMap<Integer, Integer> map = new HashMap<>();

    @Operation
    public Integer put(@Param(name = "key") int key, int value) {
        return map.put(key, value);
    }

    @Operation
    public Integer get(@Param(name = "key") int key) {
        return map.get(key);
    }

    @Test
    public void test() {
        LinChecker.check(HashMapLinearizabilityTest.class);
    }
    
    @Override
    protected Object extractState() {
        return map;
    }
}
```


**Test output**

```
= Invalid execution results: =
Init part:
[put(1, 2): null, put(4, 6): null, get(5): null, put(3, -6): null, put(1, -8): 2]
Parallel part:
| get(4):     6    | put(2, 1):  null |
| get(2):     null | put(5, 4):  null |
| put(5, -8): null | get(3):     -6   |
| get(3):     -6   | get(4):     6    |
| put(3, 5):  -6   | put(1, -4): -8   |
Post part:
[put(5, -8): 4, put(5, -2): -8, get(1): -4, put(2, -8): 1, get(1): -4]
```