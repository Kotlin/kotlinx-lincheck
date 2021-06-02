## Check the algorithm for progress guarantees

An algorithm without explicit synchronization may still be blocking and checking for liveness bugs is a non-trivial task.

Consider a simple example: you have some data, with non-atomic reads and writes.
One thread is updating the current data calling `data.write(newData)` and several threads are calling `data.read()`.
To make reads and writes atomic without using locks, we use versions of the data and get the following algorithm:

TODO: String is just a reference, there is no need to use such a synchronization here. Let's store two separate fields.

```kotlin
class DataHolder {
    var data: String = "aaa"
    @Volatile var version = 0

    fun write(new: String) { // single thread updater
        version++ // Start writing: locked for reads
        data = new
        version++ // End writing: allow reads
    }

    fun read(): String {
        while(true) {
            val curVersion = version
            // Is there a concurrent update?
            if (curVersion % 2 == 1) continue
            // Read the data
            val data = this.data
            // Return if version is the same
            if (curVersion == version) return data
        }
    }
}
```

Reads and writes of the data are atomic and the algorithm seems to be non-blocking. 
Let's check if the algorithm is really non-blocking:

1. According to the contract there is only one writer, so add `write(s)` operation to the non-parallel 
   operations group.
2. Checking for progress guarantees is supported in the model checking mode, set the corresponding option:
   `ModelCheckingOptions().checkObstructionFreedom(true)`.

Here is the resulting test:

```kotlin
@OpGroupConfig(name = "writer", nonParallel = true)
class DataHolderTest {
    private val dataHolder = DataHolder()

    @Operation(group = "writer") // single thread writer
    fun write(s: String) = dataHolder.write(s)

    @Operation
    fun read() = dataHolder.read()
    
    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class.java)
}
```

TODO: I would suggest adding a "To sum up" subsection to each part, and adding some words on what we have learned along with the full code. A link to the next chapter can also be put there.

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/DataHolderTest.kt).

The test will fail with the following output:

TODO: but an active lock has been DETECTED, let's make a PR
```text
= Obstruction-freedom is required but an active lock has been found =
Execution scenario (parallel part):
| write(K0a1) | read() |

= The following interleaving leads to the error =
Parallel part trace:
| write(K0a1)                                                    |                                                              |
|   write(K0a1) at DataHolderTest.write(DataHolderTest.kt:66)    |                                                              |
|     version.READ: 0 at DataHolder.write(DataHolderTest.kt:35)  |                                                              |
|     version.WRITE(1) at DataHolder.write(DataHolderTest.kt:35) |                                                              |
|     switch                                                     |                                                              |
|                                                                | read()                                                       |
|                                                                |   read() at DataHolderTest.read(DataHolderTest.kt:69)        |
|                                                                |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:42) |
|                                                                |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:42) |
|                                                                |                  <!--- active infinite loop --->             |
```

What has happened:
1. The writer (thread 1) incremented the version and before writing the new value switched it's execution to thread 2
2. The reader (thread 2) sees that the `version == 1` meaning that there is a concurrent update of the data and starts to spin
    in a loop waiting for the update to complete.
3. Thread 1 may halt indefinitely while thread 2 will stay in an active loop with no progress, BLOCKING STATE DETECTED.

The main progress guarantees for non-blocking algorithms are (in the order of weakening):
- _wait-freedom_: operation may be completed in a bounded number of steps no matter what other processes do
- _lock-freedom_: some process always makes progress, while a concrete operation may be stuck
- _obstruction-freedom_: any operation may be completed in a bounded number of steps if all the other processes stop

For now, `Lincheck` may test the algorithm for obstruction-freedom violation. However, most of the practical liveness bugs
 add an unexpeced blocking code, so obstruction-freedom check is very useful.

By this moment you have learnt about different testing modes, their applications and various ways to configure a test.
In [the next section](verification.md) we will cover some tips on the `Lincheck` verification stage.  

