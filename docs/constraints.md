## Single consumer (producer)

The contract of a data structure may set execution restrictions for some operations, such as _non-parallel_ execution.

For an example of a data structure with constraints for some operations: let's write a test for the [Multi-Producer-Single-Consumer queue](https://github.com/JCTools/JCTools/blob/66e6cbc9b88e1440a597c803b7df9bd1d60219f6/jctools-core/src/main/java/org/jctools/queues/atomic/MpscLinkedAtomicQueue.java) from [JCTools library](https://github.com/JCTools/JCTools) 
and check concurrent behaviour of `poll()`, `peek()` and `offer(x)` operations.

According to the contract, consuming `poll()` and `peek()` invocations may only be performed from a single thread. 

Here is how we can declare a group of operations for **non-parallel** execution:

1. Declare `@OpGroupConfig` annotation to create a group of operations for non-parallel execution:
name the group and set `nonParallel` parameter to true.
   
2. Add all non-parallel operations to this group via specifying the group name in the `@Operation` 
annotation.

```kotlin
import org.jctools.queues.atomic.MpscLinkedAtomicQueue
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

// declare a group of operations that should not be executed in parallel
@OpGroupConfig(name = "consumer", nonParallel = true)
public class MpscQueueTest {
    private val queue = MpscLinkedAtomicQueue<Int>()

    @Operation
    public fun offer(x: Int) = queue.offer(x)

    @Operation(group = "consumer") // all poll() invocations will be performed from the single thread
    public fun poll(): Int? = queue.poll()

    @Operation(group = "consumer") // all peek() invocations will be performed from the single thread
    public fun peek(): Int? = queue.peek()

    @Test
    fun stressTest() = StressOptions().check(this::class)
}
```

Here is an example of the scenario generated for this test:

```text
= Iteration 15 / 100 =
Execution scenario (init part):
[offer(1), offer(4), peek(), peek(), offer(-6)]
Execution scenario (parallel part):
| poll()   | offer(6)  |
| poll()   | offer(-1) |
| peek()   | offer(-8) |
| offer(7) | offer(-5) |
| peek()   | offer(3)  |
Execution scenario (post part):
[poll(), offer(-6), peek(), peek(), peek()]

```

Note that all consuming `poll()` and `peek()` invocations are performed from a single thread 
satisfying the queue contract.

## To sum up

In this section you have learnt how to set the constraints of the algorithm in the Lincheck test.

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MpscQueueTest.kt).

In [the next section](parameter-generation.md) you will learn how to configure arguments passed to the test operations
and when it may be useful.