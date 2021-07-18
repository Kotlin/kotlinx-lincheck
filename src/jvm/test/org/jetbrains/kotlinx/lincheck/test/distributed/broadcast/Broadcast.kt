/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.distributed.broadcast

import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.debugProb
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.util.*
val debugOutput = true
fun addToFile(f: (BufferedWriter) -> Unit) {
    if (!debugOutput) return
    FileOutputStream("broadcast_info.txt", true).bufferedWriter().use {
        f(it)
    }
}

data class Message(val body: String, val id: Int, val from: Int)


fun <Message, Log> Environment<Message, Log>.correctProcesses() =
    events().mapIndexed { index, list -> index to list }.filter { !it.second.any { p -> p is NodeCrashEvent } }
        .map { it.first }

fun <Message, Log> Environment<Message, Log>.sentMessages(processId: Int = nodeId) =
    events()[processId].filterIsInstance<MessageSentEvent<Message>>()

fun <Message, Log> Environment<Message, Log>.receivedMessages(processId: Int = nodeId) =
    events()[processId].filterIsInstance<MessageReceivedEvent<Message>>()

fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this
fun <Message, Log> Environment<Message, Log>.isCorrect() = correctProcesses().contains(nodeId)

abstract class AbstractPeer(protected val env: Environment<Message, Message>) : Node<Message> {
    @Validate
    fun check() {
        //addToFile { it.appendLine(env.events().flatMap { it }.toString()) }
        // All messages were delivered at most once.
        check(env.log.isDistinct()) { "Process ${env.nodeId} contains repeated messages" }
        // If message m from process s was delivered, it was sent by process s before.
        env.log.forEach { m ->
            check(env.sentMessages(m.from).map { it.message }.contains(m)) {
                m
            }
        }
        // If the correct process sent message m, it should deliver m.
        if (env.isCorrect()) {
            env.sentMessages().map { it.message }.forEach { m -> check(m in env.log) }
        }
        // If the message was delivered to one process, it was delivered to all correct processes.
        val logs = env.getLogs()
        env.log.forEach { m ->
            env.correctProcesses().forEach { check(logs[it].contains(m)) { "$m, $it" } }
        }
        // If some process sent m1 before m2, every process which delivered m2 delivered m1.
        val localMessagesOrder = Array(env.numberOfNodes) { i ->
            env.log.filter { it.from == i }.map { m -> env.sentMessages(i).map { it.message }.indexOf(m) }
        }
        localMessagesOrder.forEach { check(it.sorted() == it) }
    }
}

class Peer(env: Environment<Message, Message>) : AbstractPeer(env) {
    private val receivedMessages = Array<HashMap<Int, Int>>(env.numberOfNodes) { HashMap() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.numberOfNodes) {
        PriorityQueue { x, y -> x.id - y.id }
    }
    private val lastDeliveredId = Array(env.numberOfNodes) { -1 }

    private fun deliver(sender: Int) {
        while (undeliveredMessages[sender].isNotEmpty()) {
            val lastMessage = undeliveredMessages[sender].peek()
            if (lastMessage.id != lastDeliveredId[sender] + 1 || receivedMessages[sender][lastMessage.id]!! < (env.numberOfNodes + 1) / 2) {
                return
            }
            undeliveredMessages[sender].remove()
            lastDeliveredId[sender]++
            env.log.add(lastMessage)
        }
        //println("Finish deliver")
    }


    override fun onMessage(message: Message, sender: Int) {
        //println("In on message")
        val msgId = message.id
        val from = message.from
        if (!receivedMessages[from].contains(msgId)) {
            receivedMessages[from][msgId] = 2
            undeliveredMessages[from].add(message)
            env.broadcast(message)
        } else {
            receivedMessages[from][msgId] = receivedMessages[from][msgId]!! + 1
        }
        deliver(from)
    }

    @Operation(cancellableOnSuspension = false)
    fun send(msg: String) {
        val message = Message(body = msg, id = messageId++, from = env.nodeId)
        env.broadcast(message, false)
    }
}

class BroadcastTest {
    private fun createOptions() = DistributedOptions<Message, Message>()
        .requireStateEquivalenceImplCheck(false)
        .actorsPerThread(3)
        .invocationsPerIteration(30_000)
        .iterations(1)
        .verifier(EpsilonVerifier::class.java)

    @Test
    fun test() = createOptions()
        .nodeType(Peer::class.java, 2, 4)
        .setMaxNumberOfFailedNodes { it / 2 }
        .crashMode(CrashMode.NO_RECOVERIES)
        //.storeLogsForFailedScenario("broadcast.txt")
        .check()

    @Test//(expected = LincheckAssertionError::class)
    fun testMoreFailures() = createOptions()
        .nodeType(Peer::class.java, 3)
        .setMaxNumberOfFailedNodes { (it + 1) / 2 }
        .crashMode(CrashMode.NO_RECOVERIES)
        .invocationsPerIteration(50_000)
        .storeLogsForFailedScenario("broadcast.txt")
        .minimizeFailedScenario(false)
        .setTestMode(TestingMode.MODEL_CHECKING)
        .check()

    @Test
    fun testNoFailures() = createOptions()
        //.storeLogsForFailedScenario("broadcast_nof.txt")
        .nodeType(PeerIncorrect::class.java, 3)
        .check()

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() = createOptions()
        //.storeLogsForFailedScenario("broadcast_incorrect.txt")
        .setMaxNumberOfFailedNodes { it / 2 }
        .crashMode(CrashMode.NO_RECOVERIES)
        .actorsPerThread(3)
        .setTestMode(TestingMode.MODEL_CHECKING)
        .invocationsPerIteration(50_000)
        .minimizeFailedScenario(false)
        .nodeType(PeerIncorrect::class.java, 3)
        .check()
}