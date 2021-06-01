## Verification

Once the generated scenario is executed using the specified strategy, it is needed to verify the operation results for correctness.
By default `Lincheck` checks the results for _linearizability_, the standard correctness contract for thread-safe algorithms.

> Execution is **linearizable** if there exists an equivalent sequential execution which produces the same results 
> and does not violate the happens-before ordering of the initial execution.

Under the hood we build a transition graph of possible sequential executions, where states are test instances and edges are operations.
Using this transition graph, we try to find a path which produces same results on operations and does not violate 
the happens-before order constructed during the execution.

### Sequential specification

During verification we sequentially execute operations of the tested algorithm by default.

To be sure that the algorithm provides correct sequential behaviour, you can define it's _sequential specification_ 
via writing a simple sequential implementation of the algorithm.

> This also allows to write a single test instead of writing sequential and concurrent tests separately. 

How to define a sequential specification:

- sequential implementation of the algorithm should have the same methods as the tested data structure 

- sequential implementation class should be passed to the `sequentialSpecification` option of the testing mode.

As an example, let's define the sequential specification for the Multi-Producer-Single-Consumer queue (see the [section about execution constraints](constraints.md) for the full code).
Sequential specification for the concurrent `MpscLinkedAtomicQueue` will delegate it's operations to the `java.util.LinkedList<Int>`.
```kotlin
...
public class MpscQueueTest {
    private val queue = MpscLinkedAtomicQueue<Int>()

    @Operation public fun offer(x: Int) = ...
    @Operation public fun poll(): Int? = ...
    @Operation public fun peek(): Int? = ...
    
    // sequential implementation
    private class SequentialQueue {
        val q = LinkedList<Int>()

        // sequential implementation of all the operations from the test
        fun offer(x: Int) = q.offer(x)
        fun poll() = q.poll()
        fun peek() = q.peek()
    }

    @Test
    fun stressTest() = StressOptions()
        // pass sequential implementation class to the options
        .sequentialSpecification(SequentialQueue::class.java)
        .check(this::class)
}
```

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MpscQueueTest.kt).

### States equivalency

To make verification faster you can decrease the number of states in the transition graph. For that, define 
the equivalency relation between test instance states via `equals()` and `hashCode()` implementations. 

Lincheck provides the following way to do that:

- extend `VerifierState` class 
- implement the `extractState()` function: **implementation should not modify a test instance**

>`VerifierState` gets the state of the test instance calling `extractState()`, caches the extracted state representation, and uses it for `equals()` and `hashCode()`.

Let's define the external state of some data structures from the previous sections:

- The state of the counter from [the introduction section](lincheck-test-tutorial.md) is the value of the counter:

    ```kotlin
    class CounterTest : VerifierState() {
        private val c = Counter()
    
        @Operation fun inc() = c.inc()
        @Operation fun get() = c.get()
    
        override fun extractState(): Any = c.get()
    }
    ```
  > Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/CounterTest.kt).
  
- The state of the stack, which implementation you can find in [this section](testing-modes.md) may be presented via 
  it's string representation:
  
    ```kotlin
    class StackTest : VerifierState() {
        private val s = Stack<Int>()
    
        @Operation fun push(@Param(name = "value") value: Int) = s.push(value)
        @Operation fun pop() = s.pop()
        @Operation fun size() = s.size
   
        override fun extractState(): Any = s.toString()
    }
    ```
  > Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt).
  
[The next section](blocking-data-structures.md) covers a more advanced topic of testing blocking data structures.