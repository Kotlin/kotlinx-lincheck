package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.CoroutineScope

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
    suspend fun send(message: Message, receiver: Int)

    /**
     * Sends the specified [message] to all processes (from 0 to
     * [numberOfNodes]).
     */
    suspend fun broadcast(message: Message) {
        for (i in 0 until numberOfNodes) {
            if (i == nodeId) {
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

    fun getLogs(): Array<List<Log>>

    suspend fun withTimeout(ticks: Int, block: suspend CoroutineScope.() -> Unit) : Boolean

    suspend fun sleep(ticks: Int)

    fun setTimer(name: String, ticks: Int, f: suspend () -> Unit)

    fun cancelTimer(name: String)
}

sealed class Event
data class MessageSentEvent<Message>(
    val message: Message,
    val sender: Int,
    val receiver: Int,
    val id: Int,
    val clock: IntArray
) : Event() {
    override fun toString() = "[$sender]: Send message $message to $receiver, id=$id, clock=${clock.toList()}"
}

data class MessageReceivedEvent<Message>(
    val message: Message,
    val sender: Int,
    val receiver: Int,
    val id: Int,
    val clock: IntArray
) : Event() {
    override fun toString() = "[$receiver]: Received message $message from $sender, id=$id, clock=${clock.toList()}"
}

data class LogEvent<Log>(val message: Log, val sender: Int, val clock: IntArray) : Event()

data class NodeCrashEvent(val iNode: Int, val clock: IntArray) : Event() {
    override fun toString() = "[$iNode]: Node crashed, clock=${clock.toList()}"
}

data class ProcessRecoveryEvent(val iNode: Int, val clock: IntArray) : Event() {
    override fun toString() = "[$iNode]: Node recovered, clock=${clock.toList()}"
}

data class OperationStartEvent(val iNode: Int, val opId: Int, val clock: IntArray) : Event() {
    override fun toString() = "[$iNode]: Operation $opId started, clock=${clock.toList()}"
}

data class CrashNotificationEvent(val iNode: Int, val crashedNode: Int, val clock: IntArray) : Event() {
    override fun toString() = "[$iNode]: Receive crash notification from $crashedNode, clock=${clock.toList()}"
}