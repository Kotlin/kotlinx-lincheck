## Parameter generation

Till this moment in the guide we took for granted that arguments for test operations are somehow generated under the hood by `Lincheck`.
In this section you will learn how you may configure generation of arguments.

Consider the implementation of the custom `MultiMap` (bugged, of course) backed with `ConcurrentHashMap`:

```kotlin
class MultiMap {
    val map = ConcurrentHashMap<Int, List<Int>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun addBroken(key: Int, value: Int) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }
}
```

We are going to test concurrent execution of `add(key, value)` and `get(key)` operations. Incorrect interleaving is more 
likely to be detected if we increase the contention to access the small range of keys.

For this we can configure the generator for a `key: Int` parameter:
1. Declare the `@Param` annotation.
2. Specify the integer generator class: `@Param(gen = IntGen::class)`. 
   
   Out of the box `Lincheck` supports random parameter generators for almost all primitives and strings.
3. Define the range of values to be generated via the string configuration: `@Param(conf = "1:2")`.
4. Specify the parameter configuration name (`@Param(name = "key")`) to share it for several operations.

Below is the `MultiMap` stress test that will generate keys in the range of `[1..2]`: 

```kotlin
@Param(name = "key", gen = IntGen::class, conf = "1:2")
class MultiMapTest {
    private val map = MultiMap()

    @Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.addBroken(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    @Test
    fun stressTest() = StressOptions()
        .requireStateEquivalenceImplCheck(false)
        .check(this::class.java)
}
```

> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).

Run the `stressTest()` and see the following output:

```text
= Invalid execution results =
Parallel part:
| add(1, 1): void | add(1, 4): void |
Post part:
[get(1): [4]]
```

Due to the small range of keys in the `MultiMap` (`[1..2]`), 
`Lincheck` quickly revealed the race in the `add(key, value)` implementation: during 2 concurrent writes one value update was lost. 

`MultiMap` implementation uses `java.util.concurrent.ConcurrentHashMap` as a building block and testing in the model checking mode may take a while due to the significant number of interleavings to check. 
Considering implementation of the `ConcurrentHashMap` to be correct we can optimize and increase coverage of model checking. 
Go to [the next section](modular-testing.md) for details.