## Testing blocking data structures

`Lincheck` supports testing blocking data structures implemented via [suspending functions](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md#coroutines-overview)
from Kotlin language. The examples of such data structures from the Kotlin Coroutines library 
are mutexes, semaphores, and channels (see the [corresponding guide](https://kotlinlang.org/docs/reference/coroutines/coroutines-guide.html)
to understand what these data structures 

TODO: let's say that we use the dual data structure formalism (links to paper and hydra talk) and describe the formalism in a couple of sentences with an example (we have a really nice description in the paper). Then we can say, that you should not do anything special to start testing blocking algorithms with Lincheck -- it will handle suspensions automatically. Then you have to provide an example of how sequential specification can be implements (just to show that everything is the same and that even _blocking_ algos can have sequential specification, and that this is toatally fine). At the end, there should be a non-trivial part on state equivalence. That's how I see this, not how it should be done. 

To write a Lincheck test for a blocking data structure, you just need to mark 
the corresponding operations delegated to the suspending functions with a `suspend` modifier.

Here is how we can write a test for the basic communication primitive, a [rendezvous channel](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/index.html):

* Mark test operations corresponding to the suspending `send(value)` and `receive` operations with a 
    `suspend` modifier
    

```kotlin
@Param(name = "value", gen = IntGen::class, conf = "1:5")
class RendezvousChannelTest {
    private val ch = Channel<Int>()

    @Operation // suspending operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation // suspending operation
    suspend fun receive() = ch.receive()

    @Test
    fun stressTest() = StressOptions()
        .check(this::class)
}
```

### Handle exception as a result

TODO: I think that this feature does not relate to the coroutines support, and is quite important to test practical API. Let's discuss it somewhere above. 

A channel can be closed. According to the `close` documentation:

> A channel that was closed without a cause throws a `ClosedSendChannelException` on attempts to 
> `send` or `offer` and `ClosedReceiveChannelException` on attempts to `receive`.

To handle an exception thrown by the operation as normal result we should pass it to the `handleExceptionsAsResult` option of the  `@Operation` annotation, like this:

```kotlin
@Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receive() = ch.receive()
```

See the complete test of a closing rendezvous channel:

```kotlin
@Param(name = "value", gen = IntGen::class, conf = "1:5")
class RendezvousChannelTest {
    private val ch = Channel<Int>()

    @Operation(handleExceptionsAsResult = [ClosedSendChannelException::class])
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receive() = ch.receive()

    @Operation
    fun close() = ch.close()

    @Test
    fun stressTest() = StressOptions()
        .check(this::class)
}
```

Let's write a test for a [buffered channel](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/index.html)
 defining the equivalency relation on the states of a channel; see the ssection on [verification tips](verification.md) for details.

For a buffered channel, the externally observable state may include: 
- elements from the buffer
- waiting `send` operations 
- whether the channel is closed

Here is the example of the buffered channel external state definition:

```kotlin
override fun extractState(): Any {
    val elements = mutableListOf<Int>()
    while (!ch.isEmpty) elements.add(ch.poll()!!)
    val closed = ch.isClosedForSend
    return elements to closed
}
```

Below is the complete example of a buffered channel test:

```kotlin
@Param(name = "value", gen = IntGen::class, conf = "1:5")
class BufferedChannelTest : VerifierState() {
    private val ch = Channel<Int>(3)

    @Operation(handleExceptionsAsResult = [ClosedSendChannelException::class])
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receive() = ch.receive()

    @Operation
    fun close() = ch.close()

    override fun extractState(): Any {
        val elements = mutableListOf<Int>()
        while (!ch.isEmpty && !ch.isClosedForReceive) elements.add(ch.poll()!!)
        val closed = ch.isClosedForSend
        return elements to closed
    }

    @Test
    fun stressTest() = StressOptions().check(this::class)
}
```