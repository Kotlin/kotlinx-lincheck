This is an introduction section that will help you with the set-up necessary to follow the examples of the guide 
and introduce the basic Lincheck API.

## Create a project

1. Open a Kotlin project in IntelliJ IDEA. If you don't have a project, [create one](https://kotlinlang.org/docs/jvm-get-started.html).

2. Open the `main.kt` file in `src/main/kotlin`. The `main.kt` file contains sample code that will print `Hello World!`.

2. Change code in the `main()` function to:

    ```kotlin
    class Counter {
        @Volatile
        private var value = 0
   
        fun inc(): Int = ++value
        fun get() = value
   }
    ```
   Now we are going to check whether this implementation of a counter is thread-safe using `Lincheck`.
   
## Add dependencies

Open the `build.gradle(.kts)` file, make sure that you have `mavenCentral()` in the list of repositories and add the 
following dependencies to the Gradle configuration.

```groovy
repositories {
    mavenCentral()
}

dependencies {
   // Lincheck dependency
   implementation "org.jetbrains.kotlinx:lincheck:2.12"
   
   // This dependency will allow you to work with kotlin.test and JUnit:
   testImplementation "org.jetbrains.kotlin:kotlin-test"
}
```

## Create a test
 
Create `CounterTest.kt` file in the directory `src/test/kotlin` and put the following code there:

```kotlin
class CounterTest {
   private val c = Counter() // initial state

   // operations on the Counter
   @Operation fun inc() = c.inc()
   @Operation fun get() = c.get()

   @Test // JUnit
   fun test() = StressOptions().check(this::class.java) // magic button
}
```

This is a basic Lincheck test that will automatically: 

* Generate several random concurrent scenarios.
* Execute each of them a lot of times.
* Verify that results of every invocation are correct (linearizable for example). 

## Run the test

Run the test and you will see the following results:

```text
= Invalid execution results =
Parallel part:
| inc(): 1 | inc(): 1 |
```

Lincheck found the execution scenario that violates atomicity of the `Counter`: 
two concurrent increment invocations ended with the same result `1`, one increment was lost.
Obviously, this behaviour of the counter is incorrect.

## Trace the invalid execution

Besides the invalid execution results, you can also get the exact trace of thread interleaving that lead to the error.

For that you just need to switch the testing mode: replace the stress testing mode options `StressOptions()` 
with the model checking options `ModelCheckingOptions()` in the `test()` method of the `CounterTest` class 
and run the test again. 
 
```kotlin
class CounterTest {
   private val c = Counter()
   
   @Operation fun getAndInc() = c.getAndInc()
   @Operation fun get() = c.get()
    
   @Test // model checking mode
   fun test() = ModelCheckingOptions().check(this::class.java)
}
```

Now you see the whole execution trace leading to the incorrect results:
    
```text
= Invalid execution results =
Parallel part:
| inc(): 1 | inc(): 1 |
= The following interleaving leads to the error =
Parallel part trace:
|                      | inc()                                                        |
|                      |   inc(): 1 at CounterTest.inc(CounterTest.kt:146) |
|                      |     value.READ: 0 at Counter.inc(CounterTest.kt:105)           |
|                      |     switch                                                   |
| inc(): 1             |                                                              |
|   thread is finished |                                                              |
|                      |     value.WRITE(1) at Counter.inc(CounterTest.kt:105)          |
|                      |     value.READ: 1 at Counter.inc(CounterTest.kt:105)           |
|                      |   result: 1                                                  |
|                      |   thread is finished                                         |
```
    
Let's read the trace:

1. Thread 2 read the current value of the counter `value.READ: 0`
2. The execution switched to Thread 1
3. Thread 1 executed `inc()` completely and returned `1`
4. The execution switched back to the Thread 2
5. Thread 2 increments the old value of the counter that was read before the switch and returned `1`


## Test structure

Here are the steps to write the test:

1. Create an instance of the `Counter` as an initial state.
2. List operations defined on the `Counter` and mark them with `@Operation` annotation.
3. Specify the execution strategy: 
   - for _stress testing mode_ use stress testing options: `StressOptions()`
   - for _model checking mode_ use model checking options: `ModelCheckingOptions()`
4. Run the analysis by invoking the `StressOptions().check(..)` on the testing class.

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/CounterTest.kt).

Now you got familiar with the basic Lincheck API and tried out different testing modes.
Follow [the next section](testing-modes.md) to know more about stress testing and model checking modes and configuration of the test execution.