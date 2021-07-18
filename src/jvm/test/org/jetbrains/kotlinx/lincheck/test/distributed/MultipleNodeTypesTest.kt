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

package org.jetbrains.kotlinx.lincheck.test.distributed

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage.*
import org.junit.Test
import java.lang.IllegalArgumentException

class Pinger(val env: Environment<PingPongMessage, Unit>) : Node<PingPongMessage> {
    private val signal = Signal()
    private val pongerAddress = env.getAddressesForClass(Ponger::class.java)!![0]

    override fun onMessage(message: PingPongMessage, sender: Int) {
        when (message) {
            is Pong -> signal.signal()
            else -> throw IllegalArgumentException("Unexpected message type")
        }
    }

    @Operation
    suspend fun ping(): Boolean {
        env.send(Ping, pongerAddress)
        signal.await()
        return true
    }
}

class Ponger(val env: Environment<PingPongMessage, Unit>) : Node<PingPongMessage> {
    override fun onMessage(message: PingPongMessage, sender: Int) {
        when (message) {
            is Ping -> env.send(Pong, sender)
            else -> throw IllegalArgumentException("Unexpected message type")
        }
    }
}

class MultipleNodeTypesTest {
    @Test
    fun test() {
        LinChecker.check(
            Pinger::class.java,
            DistributedOptions<PingPongMessage, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .threads(2)
                .nodeType(Ponger::class.java, 1)
                .invocationsPerIteration(100)
                .iterations(100)
                .sequentialSpecification(PingPongMock::class.java)
                .actorsPerThread(2)
        )
    }
}