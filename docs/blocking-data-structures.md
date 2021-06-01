## Testing blocking data structures

`Lincheck` supports testing blocking data structures implemented via [suspending functions](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md#coroutines-overview)
from Kotlin language. The examples of such data structures from the Kotlin Coroutines library 
are mutexes, semaphores, and channels (see the [corresponding guide](https://kotlinlang.org/docs/reference/coroutines/coroutines-guide.html)
to understand what these data structures are).

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