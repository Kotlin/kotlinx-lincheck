## Modular testing

It is a common pattern to use linearizable data structures as building blocks of other ones.
The number of all possible interleavings for non-trivial algorithms usually is enormous.

To reduce the amount of redundant interleavings we can use modular testing option supported by the model checking mode.
Modular testing allows to treat operations of the internal data structures to be atomic.

Consider, for an example the `MultiMap` from the [previous section](#parameter-generation.md) 
backed with a `java.util.concurrent.ConcurrentHashMap`. 
We can rely on the `ConcurrentHashMap` to be linearizable and guarantee all it's operations to be atomic.

See the example of specifying this in the `ModelCheckingOptions`:

```kotlin
@Test
fun runModelCheckingTest() = ModelCheckingOptions()
        .requireStateEquivalenceImplCheck(false)
        .addGuarantee(forClasses(ConcurrentHashMap::class).allMethods().treatAsAtomic())
        .check(this::class)
```

TODO: with the modular testing you can examine _all_ possible interleaving in the MultiMap test from the previous chapter, with any number of context switches. This is my guess, check it :) (you can pass Int.MAX_VALUE as the number of invocations)
Not sure that we should put this example (of analysing all interleavings) here, we may just refer to the full code.

## To sum up 

In this section you have learnt how to optimize testing of data structures that use other correct data structures in their implementation.
> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).

In [the next section](constraints.md) you will learn how to configure test execution if the contract of the data structure sets
any constraints (for example single-consumer queues).