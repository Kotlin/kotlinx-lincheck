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

package org.jetbrains.kotlinx.lincheck.test.distributed.totalorder

import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent

class SkeenAlgorithmIncorrect(env: Environment<Message, Message>) : OrderCheckNode<Message>(env) {
    var clock = 0
    var opId = 0
    val resChannel = Channel<Int>(Channel.UNLIMITED)
    val replyTimes = mutableListOf<Int>()
    val messages = mutableSetOf<RequestMessage>()

    override fun onMessage(message: Message, sender: Int) {
        clock = maxOf(clock, message.clock) + 1
        when (message) {
            is RequestMessage -> {
                messages.remove(message)
                messages.add(message)
                if (!message.finalized) env.send(Reply(++clock), sender)
                deliver()
            }
            is Reply -> {
                replyTimes.add(message.clock)
                if (replyTimes.size == env.numberOfNodes - 1) {
                    resChannel.offer(replyTimes.maxOf { it })
                }
            }
        }
    }

    override fun stateRepresentation(): String {
        val sb = StringBuilder()
        sb.append("clock=$clock, ")
        sb.append("messages=")
        sb.append(messages)
        sb.append(", replies=")
        sb.append(replyTimes)
        sb.append(", log=")
        sb.append(env.log)
        return sb.toString()
    }

    private fun deliver() {
        while (true) {
            val msg = messages.minByOrNull { it.time }

            // env.recordInternalEvent("Min message $msg"
            if (msg?.finalized != true) return
            messages.removeIf { it == msg }
            env.recordInternalEvent("Add message $msg")
            check(messages.none { it.time == msg.time })
            env.log.add(msg)
        }
    }

    //@Validate
    fun validateAllReceived() {
        val logs = env.getLogs().toList()
        for (l in logs) {
            for (i in l.indices) {
                for (j in i + 1 until l.size) {
                    check(logs.none {
                        val first = it.lastIndexOf(l[i])
                        val second = it.lastIndexOf(l[j])
                        first != -1 && second != -1 && first >= second
                    }) {
                        "logs=$logs, first=${l[i]}, second=${l[j]}"
                    }
                }
            }
        }
        val sent = env.events().flatMap {
            it.filterIsInstance<MessageSentEvent<Message>>().map { it.message }.filterIsInstance<RequestMessage>()
        }
        sent.forEach { m ->
            check(logs.filterIndexed { index, _ -> index != m.from }.all { it.contains(m) }) {
                m.toString()
            }
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun broadcast() {
        if (env.numberOfNodes == 1) return
        replyTimes.clear()
        ++clock
        val msg = RequestMessage(from = env.nodeId, id = opId++, clock = clock, finalized = false, time = clock)
        env.broadcast(msg)
        val maxTime = resChannel.receive()
        val finalMsg = RequestMessage(
            from = env.nodeId, id = msg.id, clock = ++clock, finalized = true, time = maxTime
        )
        env.broadcast(finalMsg)
    }
}