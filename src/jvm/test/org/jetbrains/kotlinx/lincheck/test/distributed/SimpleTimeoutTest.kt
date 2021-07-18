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

package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test

sealed class PingPongMessage
object Ping : PingPongMessage()
object Pong : PingPongMessage()

class PingPongNode(val env: Environment<PingPongMessage, Unit>) : Node<PingPongMessage> {
    val semaphore = Semaphore(1, 1)
    var hasResult = false
    override fun onMessage(message: PingPongMessage, sender: Int) {
        when (message) {
            is Ping -> env.send(Pong, sender)
            is Pong -> if (semaphore.availablePermits == 0) {
                hasResult = true
                semaphore.release()
            }
        }
    }

    @Operation
    suspend fun ping(): Boolean {
        hasResult = false
        if (env.nodeId == 0) {
            return true
        }
        while (true) {
            env.send(Ping, 0)
            env.withTimeout(5) {
                semaphore.acquire()
            }
            if (hasResult) return true
        }
    }
}

class PingPongMock {
    suspend fun ping() = true
}

class SimpleTimeoutTest {
    @Test
    fun test() {
        LinChecker.check(
            PingPongNode::class.java,
            DistributedOptions<PingPongMessage, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .threads(2)
                .networkReliable(false)
                .invocationsPerIteration(100)
                .iterations(100)
                .sequentialSpecification(PingPongMock::class.java)
                .actorsPerThread(2)
        )
    }
}