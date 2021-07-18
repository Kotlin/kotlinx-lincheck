/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.TestNodeExecution
import java.lang.Integer.max
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class DistributedRunnerContext<Message, Log>(
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    val scenario: ExecutionScenario,
    val runnerHash: Int,
    val stateRepresentation: Method?
) {
    val addressResolver = NodeAddressResolver(
        testCfg.testClass as Class<out Node<Message>>,
        scenario.threads, testCfg.nodeTypes.mapValues { it.value.maxNumberOfInstances to it.value.canFail },
        testCfg.maxNumberOfFailedNodesForType
    )

    lateinit var messageHandler: ChannelHandler<Pair<Int, MessageSentEvent<Message>>>

    lateinit var failureNotifications: Array<Channel<Pair<Int, IntArray>>>

    //lateinit var failureInfo: NodeFailureInfo

    lateinit var events: FastQueue<Pair<Int, Event>>

    val messageId = atomic(0)

    lateinit var testNodeExecutions: Array<TestNodeExecution>

    lateinit var testInstances: Array<Node<Message>>

    lateinit var runner: DistributedRunner<Message, Log>

    private val vectorClock = Array(addressResolver.totalNumberOfNodes) {
        IntArray(addressResolver.totalNumberOfNodes)
    }

    fun incClock(i: Int) = vectorClock[i][i]++

    fun incClockAndCopy(i: Int): IntArray {
        vectorClock[i][i]++
        return vectorClock[i].copyOf()
    }

    fun maxClock(iNode: Int, clock: IntArray): IntArray {
        for (i in vectorClock[iNode].indices) {
            vectorClock[iNode][i] = max(vectorClock[iNode][i], clock[i])
        }
        return vectorClock[iNode].copyOf()
    }

    val invocation = atomic(0)

    lateinit var taskCounter: DispatcherTaskCounter

    lateinit var dispatchers: Array<NodeDispatcher>

    lateinit var logs: Array<List<Log>>

    val probabilities = Array(addressResolver.totalNumberOfNodes) {
        Probability(testCfg, this, it)
    }

    val executors = Array<ExecutorService>(addressResolver.totalNumberOfNodes) {
        Executors.newSingleThreadExecutor { r -> NodeDispatcher.NodeTestThread(it, runnerHash, r) }
    }

    val nextNumberOfCrashes = atomic(0)

    val crashInfo = atomic(NodeCrashInfo.initialInstance(testCfg, this))

    val initialNumberOfTasks =
        2 * addressResolver.totalNumberOfNodes + addressResolver.totalNumberOfNodes * addressResolver.totalNumberOfNodes

    val initialTasksForNode = addressResolver.totalNumberOfNodes + 2

    fun getStateRepresentation(iNode: Int) = testInstances[iNode].stateRepresentation()

    fun reset() {
        invocation.incrementAndGet()
        nextNumberOfCrashes.lazySet(0)
        crashInfo.lazySet(NodeCrashInfo.initialInstance(testCfg, this))
        taskCounter = DispatcherTaskCounter(initialNumberOfTasks)
        dispatchers = Array(addressResolver.totalNumberOfNodes) {
            NodeDispatcher(it, taskCounter, runnerHash, executors[it])
        }
        logs = Array(addressResolver.totalNumberOfNodes) {
            emptyList()
        }
        failureNotifications = Array(addressResolver.totalNumberOfNodes) {
            Channel(UNLIMITED)
        }
        events = FastQueue()
        vectorClock.forEach { it.fill(0) }
        messageHandler = ChannelHandler(testCfg.messageOrder, addressResolver.totalNumberOfNodes)
        val exp = 3
        probabilities.forEach { it.reset(exp) }
        messageId.lazySet(0)
    }

    fun crashNode(iNode: Int): Boolean {
        while (true) {
            val prev = crashInfo.value
            val newInfo = prev.crashNode(iNode) ?: return false
            if (crashInfo.compareAndSet(prev, newInfo)) return true
        }
    }

    fun recoverNode(iNode: Int) {
        while (true) {
            val prev = crashInfo.value
            val newInfo = prev.recoverNode(iNode)
            if (crashInfo.compareAndSet(prev, newInfo)) return
        }
    }

    fun setNetworkPartition(iNode: Int, receiver: Int): Boolean {
        while (true) {
            val prev = crashInfo.value
            val newInfo = prev.setNetworkPartition(iNode, receiver) ?: return false
            if (crashInfo.compareAndSet(prev, newInfo)) {
                val delayTimeout = probabilities[iNode].networkRecoveryDelay()
                GlobalScope.launch {
                    delay(delayTimeout.toLong())
                    recoverNetworkPartition(iNode, receiver)
                }
            }
        }
    }

    private fun recoverNetworkPartition(iNode: Int, receiver: Int) {
        while (true) {
            val prev = crashInfo.value
            val newInfo = prev.recoverNetworkPartition(iNode, receiver)
            if (crashInfo.compareAndSet(prev, newInfo)) {
                return
            }
        }
    }
}
