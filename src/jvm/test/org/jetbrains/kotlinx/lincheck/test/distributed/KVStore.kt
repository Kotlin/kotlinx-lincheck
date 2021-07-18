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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test

sealed class Message
data class PutRequest(val key: Int, val value: Int) : Message()
data class PutResponse(val prevValue: Int?) : Message()
data class GetRequest(val key: Int) : Message()
data class GetResponse(val value: Int?) : Message()

class Client(val env: Environment<Message, Unit>) : Node<Message> {
    private val server = env.getAddressesForClass(Server::class.java)!![0]
    private val resultsChannel = Channel<Int?>(UNLIMITED)

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is PutResponse -> resultsChannel.offer(message.prevValue)
            is GetResponse -> resultsChannel.offer(message.value)
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: Int): Int? {
        env.send(GetRequest(key), server)
        return resultsChannel.receive()
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: Int, value: Int): Int? {
        env.send(PutRequest(key, value), server)
        return resultsChannel.receive()
    }
}

class Server(val env: Environment<Message, Unit>) : Node<Message> {
    private val storage = mutableMapOf<Int, Int>()

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is PutRequest -> env.send(PutResponse(storage.put(message.key, message.value)), sender)
            is GetRequest -> env.send(GetResponse(storage[message.key]), sender)
        }
    }
}

class SeqSpec {
    val storage = mutableMapOf<Int, Int>()
    @Operation
    suspend fun put(key : Int, value: Int) = storage.put(key, value)

    @Operation
    suspend fun get(key: Int) = storage[key]
}

class Test {
    private fun createOptions() =
        DistributedOptions<Message, Unit>()
            .sequentialSpecification(SeqSpec::class.java)
            .invocationsPerIteration(3_000)
            .iterations(10)
            .nodeType(Server::class.java, minNumberOfInstances = 1, maxNumberOfInstances = 1)
            .nodeType(Client::class.java, 3)
            .requireStateEquivalenceImplCheck(false)
            .storeLogsForFailedScenario("kvstore.txt")

    @Test(expected = LincheckAssertionError::class)
    fun testFail() = createOptions()
        .crashMode(CrashMode.ALL_NODES_RECOVER)
        .setMaxNumberOfFailedNodes(Client::class.java) { it }
        .check()

    @Test
    fun test() = createOptions().check()
}