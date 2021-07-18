package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.kotlinx.lincheck.Actor
import kotlin.coroutines.CoroutineContext

/**
 * Environment interface for communication with other processes.
 */
interface Environment<Message, Log> {
    /**
     * Identifier of this node (from 0 to [numberOfNodes]).
     */
    val nodeId: Int

    /**
     * The total number of nodes in the system.
     */
    val numberOfNodes: Int

    /**
     * Returns identifiers of nodes of the exact class [cls].
     **/
    fun getAddressesForClass(cls: Class<out Node<Message>>): List<Int>?

    /**
     * Sends the specified [message] to the process [receiver] (from 0 to [numberOfNodes]).
     */
    fun send(message: Message, receiver: Int)

    /**
     * Sends the specified [message] to all processes (from 0 to
     * [numberOfNodes]).
     */
    fun broadcast(message: Message, skipItself: Boolean = true) {
        for (i in 0 until numberOfNodes) {
            if (i == nodeId && skipItself) {
                continue
            }
            send(message, i)
        }
    }

    /**
     * Logs for current node.
     */
    val log: MutableList<Log>

    /**
     * Test execution events for each node (including sending and receiving messages,
     * node failures, logs and etc.)
     * Should be called only in validation functions to check the invariants.
     */
    fun events(): Array<List<Event>>

    /**
     * Returns the logs for all nodes. Should be called only in validation functions after the execution.
     */
    fun getLogs(): Array<List<Log>>

    /**
     * Runs the specified [block] of code with a specified timeout and finishes if timeout was exceeded.
     * The execution will not be finish until the block is executed ot timeout is exceeded.
     */
    suspend fun withTimeout(ticks: Int, block: suspend CoroutineScope.() -> Unit): Boolean

    /**
     * Can be used as a safe [kotlinx.coroutines.delay]. The execution will not be finished until the program is resumed.
     */
    suspend fun sleep(ticks: Int)

    /**
     * Sets a timer with the specified [name].
     * Timer executes function [f] periodically each [ticks] time,
     * until the execution is over.
     */
    fun setTimer(name: String, ticks: Int, f: suspend () -> Unit)

    /**
     * Cancels timer with the name [name].
     */
    fun cancelTimer(name: String)

    /**
     * Records an internal event [InternalEvent]. Can be used for debugging purposes.
     * [message] is stored in [InternalEvent.message].
     */
    fun recordInternalEvent(message: String)

    val coroutineContext: CoroutineContext
}

/**
 * Event for a node.
 */
sealed class Event

/**
 *
 */
data class MessageSentEvent<Message>(
    val message: Message,
    val receiver: Int,
    val id: Int,
    val clock: IntArray,
    val state: String
) : Event() {
    override fun toString(): String =
        "Send $message to $receiver, messageId=$id, clock=${clock.toList()}" + if (state.isNotBlank()) ", state=$state" else ""
}

data class MessageReceivedEvent<Message>(
    val message: Message,
    val sender: Int,
    val id: Int,
    val clock: IntArray,
    val state: String
) : Event() {
    override fun toString(): String =
        "Received $message from $sender, messageId=$id, clock=${clock.toList()}" + if (state.isNotBlank()) ", state={$state}" else ""
}

data class InternalEvent(val message: String, val clock: IntArray, val state: String) :
    Event() {
    override fun toString(): String =
        "$message, clock=${clock.toList()}" + if (state.isNotBlank()) ", state={$state}" else ""
    }

data class NodeCrashEvent(val clock: IntArray, val state: String) : Event()

data class ProcessRecoveryEvent(val clock: IntArray, val state: String) : Event()

data class OperationStartEvent(val actor: Actor, val clock: IntArray, val state: String) :
    Event() {
    override fun toString(): String =
        "Start operation $actor, clock=${clock.toList()}" + if (state.isNotBlank()) ", state={$state}" else ""
    }

data class CrashNotificationEvent(
    val crashedNode: Int,
    val clock: IntArray,
    val state: String
) : Event()

data class SetTimerEvent(val timerName: String, val clock: IntArray, val state: String) : Event()

data class TimerTickEvent(val timerName: String, val clock: IntArray, val state: String) : Event()

data class CancelTimerEvent(val timerName: String, val clock: IntArray, val state: String) : Event()

data class NetworkPartitionEvent(val partitions: List<Set<Int>>, val partitionCount: Int) : Event()

data class NetworkRecoveryEvent(val partitionCount: Int) : Event()