## Check the algorithm for progress guarantees

An algorithm without explicit synchronization may still be blocking and checking for liveness bugs is a non-trivial task.

Consider a simple example: you have some data, with non-atomic reads and writes.
One thread is updating the current data calling `data.write(newData)` and several threads are calling `data.read()`.
To make reads and writes atomic without using locks, we use versions of the data and get the following algorithm:

```kotlin
class DataHolder {
    var first: Int = 42
    var second: Int = 7
    @Volatile var version = 0

    fun write(newFirst: Int, newSecond: Int) { // single thread updater
        version++ // lock the holder for reads
        first = newFirst
        second = newSecond
        version++ // release the holder for reads
    }

    fun read(): Pair<Int, Int> {
        while(true) {
            val curVersion = version
            // Is there a concurrent update?
            if (curVersion % 2 == 1) continue
            // Read the data
            val first = this.first
            val second = this.second
            // Return if version is the same
            if (curVersion == version) return first to second
        }
    }
}
```

Reads and writes of the data are atomic and the algorithm seems to be non-blocking. 
Let's check if the algorithm is really non-blocking:

1. According to the contract there is only one writer, so add `write(first, second)` operation to the non-parallel 
   operations group.
2. Checking for progress guarantees is supported in the model checking mode, set the corresponding option:
   `ModelCheckingOptions().checkObstructionFreedom(true)`.

Here is the resulting test:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

@OpGroupConfig(name = "writer", nonParallel = true)
class DataHolderTest {
    private val dataHolder = DataHolder()

    @Operation(group = "writer") // single thread writer
    fun write(first: Int, second: Int) = dataHolder.write(first, second)

    @Operation
    fun read() = dataHolder.read()
    
    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class.java)
}
```

The test will fail with the following output:

TODO: but an active lock has been DETECTED, let's make a PR
```text
= Obstruction-freedom is required but an active lock has been found =
Execution scenario (parallel part):
| write(-4, -8) | read() |

= The following interleaving leads to the error =
Parallel part trace:
| write(-4, -8)                                                  |                                                                 |
|   write(-4,-8) at DataHolderTest.write(DataHolderTest.kt:72)   |                                                                 |
|     version.READ: 0 at DataHolder.write(DataHolderTest.kt:36)  |                                                                 |
|     version.WRITE(1) at DataHolder.write(DataHolderTest.kt:36) |                                                                 |
|     switch                                                     |                                                                 |
|                                                                | read()                                                          |
|                                                                |   read() at DataHolderTest.read(DataHolderTest.kt:75)           |
|                                                                |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:44)    |
|                                                                |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:44)    |
```

What has happened:
**T1:** The 1st thread (the writer) incremented the version and before writing the new values switched it's execution to the 2nd thread
**T2:** The 2nd thread (the reader) sees that the `version == 1` meaning that there is a concurrent update of the values and starts to spin
    in a loop waiting for the update to complete.
**T1:** The 1st thread may halt indefinitely while the 2nd thread will stay in an active loop with no progress, BLOCKING STATE DETECTED.

The main progress guarantees for non-blocking algorithms are (in the order of weakening):
- _wait-freedom_: operation may be completed in a bounded number of steps no matter what other processes do
- _lock-freedom_: some process always makes progress, while a concrete operation may be stuck
- _obstruction-freedom_: any operation may be completed in a bounded number of steps if all the other processes stop

For now, `Lincheck` may test the algorithm for obstruction-freedom violation. However, most of the practical liveness bugs
 add an unexpeced blocking code, so obstruction-freedom check is very useful.

## To sum up

In this section you have learnt how you can check your algorithm for liveness bugs using model checking testing mode.

> Get the full code of the example [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/DataHolderTest.kt).

By this moment you have learnt about different testing modes, their applications and various ways to configure a test.
In [the next section](verification.md) we will cover some tips on the `Lincheck` verification stage.
