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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.Event
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.NodeAddressResolver
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.TestNodeExecution
import java.util.*
import kotlin.random.Random

class ModelCheckingContext<Message, Log>(
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    val scenario: ExecutionScenario
) {
    val addressResolver = NodeAddressResolver(
        testCfg.testClass as Class<out Node<Message>>,
        scenario.threads, testCfg.nodeTypes.mapValues { it.value.maxNumberOfInstances to it.value.canFail },
        testCfg.maxNumberOfFailedNodesForType
    )

    lateinit var events: Queue<Pair<Int, Event>>

    var messageId = 0

    lateinit var testNodeExecutions: Array<TestNodeExecution>

    lateinit var testInstances: Array<Node<Message>>

    lateinit var runner: DistributedModelCheckingRunner<Message, Log>

    private val vectorClock = Array(addressResolver.totalNumberOfNodes) {
        IntArray(addressResolver.totalNumberOfNodes)
    }

    fun incClock(i: Int) = vectorClock[i][i]++

    fun incClockAndCopy(i: Int): IntArray {
        vectorClock[i][i]++
        return vectorClock[i].copyOf()
    }

    fun copyClock(i: Int) = vectorClock[i].copyOf()

    fun maxClock(iNode: Int, clock: IntArray): IntArray {
        for (i in vectorClock[iNode].indices) {
            vectorClock[iNode][i] = Integer.max(vectorClock[iNode][i], clock[i])
        }
        return vectorClock[iNode].copyOf()
    }

    var nodeCrashInfo = NodeCrashInfo(testCfg, this)

    val invocation = 0

    lateinit var logs: Array<List<Log>>

    fun getStateRepresentation(iNode: Int) = testInstances[iNode].stateRepresentation()

    fun reset() {
        nodeCrashInfo = NodeCrashInfo(testCfg, this)
        tasksId = 0
        messageId = 0
        logs = Array(addressResolver.totalNumberOfNodes) {
            emptyList()
        }
        events = LinkedList()
        vectorClock.forEach { it.fill(0) }
    }

    val generatingRandom = Random(0)

    var maxNumberOfErrors = 0

    var tasksId = 0
}