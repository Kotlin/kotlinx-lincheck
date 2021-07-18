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

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.kotlinx.lincheck.distributed.*
import kotlin.coroutines.CoroutineContext

class MCEnvironmentImpl<Message, Log>(
    override val nodeId: Int,
    override val numberOfNodes: Int,
    override val log: MutableList<Log> = mutableListOf(),
    val context: ModelCheckingContext<Message, Log>
) : Environment<Message, Log> {
    override val coroutineContext: CoroutineContext
        get() = context.runner.dispatcher

    var isFinished = true
    override fun getAddressesForClass(cls: Class<out Node<Message>>): List<Int>? = context.addressResolver[cls]

    val lastPriorities = Array(numberOfNodes) { 0 }

    override fun send(message: Message, receiver: Int) {
        if (!context.nodeCrashInfo.canSend(nodeId, receiver)) return
        val clock = context.incClockAndCopy(nodeId)
        val event = MessageSentEvent(
            message = message,
            receiver = receiver,
            id = context.messageId++,
            clock = clock,
            state = context.getStateRepresentation(nodeId)
        )
        val taskId = context.tasksId++
        context.runner.curTreeNode!!.addMessage(event.id, taskId)
        val nextTransition = context.runner.nextTransition()
        if (nextTransition == taskId) {
            //println("[$nodeId]: Fail $taskId")
            val newClock = context.incClockAndCopy(nodeId)
            context.runner.addTask(NodeCrashTask(nodeId, VectorClock(newClock), "Crash node $nodeId") {
                context.events.add(nodeId to NodeCrashEvent(newClock, context.getStateRepresentation(nodeId)))
                val tasksToRemove =
                    context.runner.tasks.filter { it.value.iNode == nodeId && it.key != taskId }.map { it.key }
                tasksToRemove.forEach { context.runner.tasks.remove(it) }
                if (context.testCfg.supportRecovery == CrashMode.NO_RECOVERIES) {
                    context.testNodeExecutions.getOrNull(nodeId)?.crashRemained()
                }
            }, taskId)
        }
        debugLogs.add("[$nodeId]: Send $message to $receiver ${event.id} ${context.runner.curTreeNode?.id}")
        //println(debugLogs.last())
        context.events.add(nodeId to event)

        context.runner.addTask(
            MessageReceiveTask(
                receiver,
                VectorClock(clock),
                "[$receiver]: Receive $message ${event.id}"
            ) {
                debugLogs.add("[$receiver]: Receive $message ${event.id} ${context.runner.curTreeNode?.id}")
                //println(debugLogs.last())
                context.incClock(receiver)
                val newclock = context.maxClock(receiver, clock)
                context.events.add(
                    receiver to
                            MessageReceivedEvent(
                                message,
                                sender = nodeId,
                                id = event.id,
                                clock = newclock,
                                state = context.getStateRepresentation(receiver)
                            )
                )
                context.testInstances[receiver].onMessage(message, nodeId)
            })
    }

    override fun events(): Array<List<Event>> {
        val events = context.events.toList().groupBy { it.first }.mapValues { it.value.map { it.second } }
        return Array(numberOfNodes) {
            events[it]!!
        }
    }

    override fun getLogs(): Array<List<Log>> = context.logs

    override suspend fun withTimeout(ticks: Int, block: suspend CoroutineScope.() -> Unit): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun sleep(ticks: Int) {
        TODO("Not yet implemented")
    }

    override fun setTimer(name: String, ticks: Int, f: suspend () -> Unit) {
        TODO("Not yet implemented")
    }

    override fun cancelTimer(name: String) {
        TODO("Not yet implemented")
    }

    override fun recordInternalEvent(message: String) {
        context.events.add(
            nodeId to InternalEvent(
                message,
                context.copyClock(nodeId),
                context.getStateRepresentation(nodeId)
            )
        )
    }
}