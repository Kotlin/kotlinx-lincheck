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

package org.jetbrains.kotlinx.lincheck.test.distributed.replicas

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.distributed.stress.withProbability
import org.junit.Test

sealed class KVMessage
data class PutKVRequest(val key: String, val value: String) : KVMessage()
data class PutKVEntry(val key: String, val value: String) : KVMessage()
data class GetKVRequest(val key: String) : KVMessage()
data class GetKVResponse(val value: String?) : KVMessage()

class ReplicaIncorrect(private val env: Environment<KVMessage, Unit>) : Node<KVMessage> {
    private val storage = mutableMapOf<String, String>()
    override fun onMessage(message: KVMessage, sender: Int) {
        when (message) {
            is PutKVRequest -> {
                storage[message.key] = message.value
                env.getAddressesForClass(ReplicaIncorrect::class.java)?.filter { it != env.nodeId }
                    ?.forEach { env.send(PutKVEntry(message.key, message.value), it) }
            }
            is PutKVEntry -> storage[message.key] = message.value
            is GetKVRequest -> env.send(GetKVResponse(storage[message.key]), sender)
            else -> throw IllegalArgumentException("Unexpected message")
        }
    }
}

class ClientIncorrect(private val env: Environment<KVMessage, Unit>) : Node<KVMessage> {
    private val signal = Signal()
    private var res: GetKVResponse? = null

    private fun getRandomReplica() = env.getAddressesForClass(ReplicaIncorrect::class.java)!!.random()

    @Operation
    suspend fun get(key: String): String? {
        val replica = getRandomReplica()
        env.send(GetKVRequest(key), replica)
        signal.await()
        return res!!.value
    }

    @Operation
    fun put(key: String, value: String) {
        val replica = getRandomReplica()
        env.send(PutKVRequest(key, value), replica)
    }

    override fun onMessage(message: KVMessage, sender: Int) {
        if (message is GetKVResponse) {
            res = message
            signal.signal()
        } else {
            throw IllegalArgumentException("Unexpected exception")
        }
    }
}

class ReplicaSpecification {
    private val storage = mutableMapOf<String, String>()

    @Operation
    suspend fun get(key: String) = storage[key]

    @Operation
    fun put(key: String, value: String) {
        storage[key] = value
    }
}

class ReplicaIncorrectTest {
    @Test(expected = LincheckAssertionError::class)
    fun test() {
        LinChecker.check(
            ClientIncorrect::class.java,
            DistributedOptions<KVMessage, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(ReplicaSpecification::class.java)
                .nodeType(ReplicaIncorrect::class.java, 1, 3)
                .threads(2)
                .invocationsPerIteration(100)
                .iterations(300)
        )
    }
}

