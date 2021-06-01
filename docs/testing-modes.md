## Lincheck testing modes

In this section we will take a closer look at the testing modes provided by Lincheck.

For an example we will test the lock-free Treiber stack algorithm. 
In addition to the standard `push(value)` and `pop()` operations, we have also implemented a non-linearizable (thus, incorrect) `size()`, 
which increases and decreases the corresponding `size` field following successful push and pop invocations.

```kotlin
class Stack<T> {
    private val top  = AtomicReference<Node<T>?>(null)
    private val _size = AtomicInteger(0)

    fun push(value: T) {
        while (true) {
            val cur = top.get()
            val newTop = Node(cur, value)
            if (top.compareAndSet(cur, newTop)) { // try to add
                _size.incrementAndGet() // <-- INCREMENT SIZE
                return
            }
        }
    }

    fun pop(): T? {
        while (true) {
            val cur = top.get() ?: return null // is stack empty?
            if (top.compareAndSet(cur, cur.next)) { // try to retrieve
                _size.decrementAndGet() // <-- DECREMENT SIZE
                return cur.value
            }
        }
    }

    val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)
```

## Stress testing

Let's write a stress test on our stack:

1. Specify the initial state of the `Stack` by calling the constructor.

2. We are testing concurrent behaviour of `push(value)`, `pop()` and `size()` operations, so list them all and mark with `@Operation` annotation.

3. Choose `StressOptions()` to run the test in the stress mode.

4. Run the test by invoking the `StressOptions().check(..)` function on the testing class.

```kotlin
class StackTest {
    private val s = Stack<Int>()

    @Operation fun push(value: Int) = s.push(value)
    @Operation fun pop() = s.pop()
    @Operation fun size() = s.size

    @Test // stress testing mode
    fun stressTest() = StressOptions().check(this::class.java)
}
```

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt).
 
Run the `stressTest()` function. It will fail with the following output:

```text
= Invalid execution results =
Parallel part:
| push(10): void | pop():  10       |
|                | size(): -1 [-,1] |
```

The stress test revealed the incorrect behaviour of `size()` implementation: negative return value. 
Here we come again to the main advantage of the model checking mode: providing a trace to reproduce the error.

## Model checking

To run model checking change execution options from `StressOptions()` to `ModelCheckingOptions()`.

> **Java 9+ support**
> 
> Please note that the current version requires the following JVM property
if the testing code uses classes from `java.util` package since
some of them use `jdk.internal.misc.Unsafe` or similar internal classes under the hood:
> ```text
> --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
> --add-exports java.base/jdk.internal.util=ALL-UNNAMED
> ```

```kotlin
class StackTest {
    private val s = Stack<Int>()

    @Operation fun push(value: Int) = s.push(value)
    @Operation fun pop() = s.pop()
    @Operation fun size() = s.size

    @Test // model checking mode
    fun runModelCheckingTest() = ModelCheckingOptions().check(this::class.java)
}
```

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt).   

Run the `runModelCheckingTest()` function and see the failure output: 

```text
= Invalid execution results =
Parallel part:
| push(10): void | pop():  10       |
|                | size(): -1 [-,1] |
---
values in "[..]" brackets indicate the number of completed operations 
in each of the parallel threads seen at the beginning of the current operation
---

= The following interleaving leads to the error =
Parallel part trace:
| push(10)                                                            |                      |
|   push(10) at StackTest.push(StackTest.kt:63)                |                      |
|     get(): null at Stack.push(StackTest.kt:21)                      |                      |
|     compareAndSet(null,Node@1): true at Stack.push(StackTest.kt:23) |                      |
|     switch                                                          |                      |
|                                                                     | pop(): 10            |
|                                                                     | size(): -1           |
|                                                                     |   thread is finished |
|     incrementAndGet(): 0 at Stack.push(StackTest.kt:24)             |                      |
|   result: void                                                      |                      |
|   thread is finished                                                |                      |

```

Let's read the trace to figure out what interleaving lead to the negative `size`:

1. Thread 1 pushes `10` onto the stack and stops before incrementing the size
2. The execution switched to Thread 2
3. Thread 2 retrieves the already pushed `10` and decreases the size to `-1`, which is incorrect since it's negative

## Configurations

We can configure scenario generation and execution strategy via setting options in the `<TestingMode>Options` class.

For our `StackTest` we have set the parameters of the generated scenario and turned on the logging: 

```kotlin
class StackTest {
    private val s = Stack<Int>()

    @Operation fun push(value: Int) = s.push(value)
    @Operation fun pop() = s.pop()
    @Operation fun size() = s.size

   @Test
   fun stressTest() = StressOptions() // stress testing mode options
      .actorsBefore(2) // Init part
      .threads(2).actorsPerThread(2) // Parallel part
      .actorsAfter(1) // Post part
      .logLevel(LoggingLevel.INFO) // Logging all executed scenarios
      .check(this::class)
}
```

If we run `stressTest()` again, this configured test the output will look like this (comments given in `<--!  -->`):

```text 
......
<--! Scenarios with correct execution resuls -->

= Iteration 24 / 100 =
Execution scenario (init part):
[size(), pop()]
Execution scenario (parallel part):
| push(8) | size() |
| size()  | pop()  |
Execution scenario (post part):
[size()]

<--! The first scenario with invalid execution results -->

= Iteration 25 / 100 =
Execution scenario (init part):
[size(), push(7)]
Execution scenario (parallel part):
| pop()  | push(8) |
| size() | pop()   |
Execution scenario (post part):
[push(7)]

<--! Lincheck tries to minimize scenario that leads to incorrect results -->

Invalid interleaving found, trying to minimize the scenario below:
.......

<--! Minimized invalid execution scenario -->

= Invalid execution results =
Init part:
[push(7): void]
Parallel part:
| pop():  null | pop(): 7 |
| size(): 1    |          |
```

## Scenario minimization

In the output above we saw the stage of invalid scenario minimization. When the invalid scenario is hit, Lincheck starts to “minimize” this scenario by trying to greedily remove operations from it 
and checking whether the test still fails, continues to remove operations until it is no longer possible to do so without also causing the test not to fail.

This stage is extremely useful for debug and turned on with `minimizeFailedScenario` option (set to`true` by default).

## Logging data structure states

Another feature useful for debug is state logging. For this, we should define a method returning a `String` representation 
of a data structure marked with `@StateRepresentation` in the test class. 

> This method should be thread-safe, non-blocking, and should not modify the data structure. 

Let's define the `String` representation of the `Stack` like this:

```kotlin
override fun toString() =
   buildString {
      append("[")
      var node = top.get()
      while (node != null) {
         append(node.value)
         node = node.next
         if (node != null) append(",")
      }
      append("]")
   }
```

And now we can define this `toString()` implementation as the stack state representation: 

```kotlin
 @StateRepresentation
 fun stackreperesentation() = s.toString()
```

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt).

During stress testing, the state representation is printed after the init and post execution parts as well as after the parallel part.
For model checking it is possible print the current state representation after each read or write event.

Running the `stressTest()` now, you will see the following output:

```text
= Invalid execution results =
Init part:
[push(5): void, push(7): void]
STATE: [7,5]
Parallel part:
| push(3): void       | pop():  7       |
| size():  3    [1,-] | size(): 2 [2,1] |
STATE: [3,5]
Post part:
[size(): 2, push(3): void, push(10): void, size(): 4]
STATE: [10,3,3,5]
```

In [the next section](constraints.md) you will learn how to configure test execution if the contract of the data structure sets
any constraints (for example single-consumer queues). 