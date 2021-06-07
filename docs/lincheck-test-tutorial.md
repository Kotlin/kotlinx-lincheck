TODO: rename the file 

This introductory section will help you with the set-up necessary to follow the examples of the guide and introduce the basic Lincheck API.

## Create a project

1. Open a Kotlin project in IntelliJ IDEA. If you don't have one, [create a new project](https://kotlinlang.org/docs/jvm-get-started.html).

2. Open the `main.kt` file in `src/main/kotlin`. The `main.kt` file contains a sample code that prints `Hello World!`.

3. Replace the code in `main.kt` with the following counter implementation:

    ```kotlin
    class Counter {
        @Volatile
        private var value = 0
   
        fun inc(): Int = ++value
        fun get() = value
   }
    ```
   Now we are going to check whether this counter is thread-safe by writing a test with `Lincheck`.
   
## Add required dependencies

First, we need to add `Lincheck` as a dependency to your project. 
Open the `build.gradle(.kts)` file, make sure that you have `mavenCentral()` in the list of repositories, and add the following dependencies to the Gradle configuration.

```groovy
repositories {
    mavenCentral()
}

dependencies {
   // Lincheck dependency
   testImplementation "org.jetbrains.kotlinx:lincheck:2.13"
   
   // This dependency will allow you to work with kotlin.test and JUnit:
   testImplementation "junit:junit:4.12"
}
```

## Write your first Lincheck test
 
Create `CounterTest.kt` file in the directory `src/test/kotlin` and put the following code there:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class CounterTest {
   private val c = Counter() // initial state

   // operations on the Counter
   @Operation fun inc() = c.inc()
   @Operation fun get() = c.get()

   @Test // JUnit
   fun test() = StressOptions().check(this::class) // the magic button
}
```

This is a basic Lincheck test that will automatically: 

1. Generate several random concurrent scenarios;
2. Examine each of them performing a lot of scenario invocations;
3. Verify that each of the observed invocation results are correct. 

## Run the test

Run the test above, and you will see the following error:

```text
= Invalid execution results =
Parallel part:
| inc(): 1 | inc(): 1 |
```

Here, `Lincheck` found an execution that violates atomicity of the `Counter` &ndash;
two concurrent increments ended with the same result `1`; so one increment was lost.
Obviously, this behavior of the counter is incorrect.

## Trace the invalid execution

Besides the invalid execution results, it is also possible to find the exact interleaving that leads to the error. 
This feature is accessible with the *model checking* testing mode, which examines many different interleavings with a bounded number of context switches.

To switch the testing mode, you need to replace the options type from `StressOptions()` 
to `ModelCheckingOptions()`.

TODO: do you need this for the counter?
> **Java 9+ support**
>
> Please note that to run the example below using Java 9 and later 
> the following JVM property is required:
> ```text
> --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
> --add-exports java.base/jdk.internal.util=ALL-UNNAMED
> ```

The updated `CounterTest` class is presented below:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class CounterTest {
   private val c = Counter()
   
   @Operation fun getAndInc() = c.getAndInc()
   @Operation fun get() = c.get()
    
   @Test
   fun test() = ModelCheckingOptions().check(this::class)
}
```

When you re-run the test with model checking instead of stress testing, you will get the execution trace leading to the incorrect results:
    
TODO: strange line numbers in the trace below

```text
= Invalid execution results =
Parallel part:
| inc(): 1 | inc(): 1 |
= The following interleaving leads to the error =
Parallel part trace:
|                      | inc()                                                 |
|                      |   inc(): 1 at CounterTest.inc(CounterTest.kt:146)     |
|                      |     value.READ: 0 at Counter.inc(CounterTest.kt:105)  |
|                      |     switch                                            |
| inc(): 1             |                                                       |
|   thread is finished |                                                       |
|                      |     value.WRITE(1) at Counter.inc(CounterTest.kt:105) |
|                      |     value.READ: 1 at Counter.inc(CounterTest.kt:105)  |
|                      |   result: 1                                           |
|                      |   thread is finished                                  |
```
    
In this trace, the following list of events have occurred:  
**T2:** The 2nd thread reads the current value of the counter (`value.READ: 0`) and pauses.    
**T1:** The 1st thread executes `inc()`, which returns `1`, and finishes.  
**T2:** The 2nd thread increments the previously read value of the counter and incorrectly updates it to `1`, returning `1` as a result.

## To sum up

Now you got familiar with the basic Lincheck API and tried out different testing modes.

> Get the full code of the example [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/CounterTest.kt).

Follow [the next section](testing-modes.md) to know more about stress testing and model checking modes and configuration of the test execution.