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


package org.jetbrains.kotlinx.lincheck.test.distributed.kvsharding

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test

sealed class KVMessage(val id: Int, val isRequest: Boolean)
class GetRequest(val key: String, id: Int) : KVMessage(id, true) {
    override fun toString() = "GetRequest(key=$key, id=$id)"
}

class GetResponse(val value: String?, id: Int) : KVMessage(id, false) {
    override fun toString() = "GetResponse(value=$value, id=$id)"
}

class PutRequest(val key: String, val value: String, id: Int) : KVMessage(id, true) {
    override fun toString() = "PutRequest(key=$key, value=$value, id=$id)"
}

class PutResponse(val previousValue: String?, id: Int) : KVMessage(id, false) {
    override fun toString() = "PutResponse(previousValue=$previousValue, id=$id)"
}

object Recover : KVMessage(0, true) {
    override fun toString() = "Recover"
}

sealed class Log
data class KVLog(val request: PutRequest, val prev: String?) : Log()
data class OpId(val id: Int) : Log()


class Shard(val env: Environment<KVMessage, Log>) : Node<KVMessage> {
    private var opId = 0
    private val semaphore = Signal()
    private var response: KVMessage? = null
    private var delegate: Int? = null

    private fun getNodeForKey(key: String) =
        ((key.hashCode() % env.numberOfNodes) + env.numberOfNodes) % env.numberOfNodes

    private fun saveToLog(request: PutRequest): String? {
        val log = env.log
        val present = log.lastOrNull { it is KVLog && it.request === request }
        if (present != null) {
            return (present as KVLog).prev
        }
        val index = log.indexOfLast { it is KVLog && it.request.key == request.key }
        val prev = if (index == -1) {
            null
        } else {
            val res = (log[index] as KVLog).request.value
            res
        }
        log.add(KVLog(request, prev))
        return prev
    }

    private fun getFromLog(key: String): String? {
        val log = env.log
        val index = log.indexOfLast { it is KVLog && it.request.key == key }
        return if (index == -1) {
            null
        } else {
            (log[index] as KVLog).request.value
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: String, value: String): String? {
        env.log.add(OpId(++opId))
        val node = getNodeForKey(key)
        val request = PutRequest(key, value, opId)
        if (node == env.nodeId) {
            return saveToLog(request)
        }
        return (send(request, node) as PutResponse).previousValue
    }

    private suspend fun send(request: KVMessage, receiver: Int): KVMessage {
        response = null
        delegate = receiver
        while (true) {
            env.send(request, receiver)
            env.withTimeout(10) {
                semaphore.await()
            }
            response ?: continue
            delegate = null
            return response!!
        }
    }

    override fun recover() {
        val id = env.log.filterIsInstance(OpId::class.java).lastOrNull()?.id ?: -1
        opId = id + 1
        env.broadcast(Recover)
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: String): String? {
        env.log.add(OpId(++opId))
        val node = getNodeForKey(key)
        if (node == env.nodeId) {
            return getFromLog(key)
        }
        val request = GetRequest(key, opId)
        return (send(request, node) as GetResponse).value
    }

    override fun onMessage(message: KVMessage, sender: Int) {
        if (message is Recover) {
            if (sender == delegate) {
                semaphore.signal()
            }
            return
        }
        if (!message.isRequest) {
            if (message.id != opId) return
            if (response == null) {
                response = message
                semaphore.signal()
            }
            return
        }
        val msg = when (message) {
            is GetRequest -> GetResponse(getFromLog(message.key), message.id)
            is PutRequest -> PutResponse(saveToLog(message), message.id)
            else -> throw IllegalStateException()
        }
        env.send(msg, sender)
    }
}

class SingleNode {
    private val storage = HashMap<String, String>()

    @Operation
    suspend fun put(key: String, value: String): String? {
        return storage.put(key, value)
    }

    @Operation
    suspend fun get(key: String): String? {
        return storage[key]
    }
}

class KVShardingTest {
    @Test
    fun testNoFailures() {
        LinChecker.check(
            ShardMultiplePutToLog::class.java,
            DistributedOptions<KVMessage, KVLogEntry>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .threads(3)
                .invocationsPerIteration(1000)
                .iterations(30)
        )
    }

    @Test
    fun test() {
        LinChecker.check(
            Shard::class.java,
            DistributedOptions<KVMessage, KVLogEntry>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .actorsPerThread(3)
                .threads(3)
                .invocationsPerIteration(1000)
                .setMaxNumberOfFailedNodes { it / 2 }
                .iterations(30)
                .crashMode(CrashMode.ALL_NODES_RECOVER)
                .storeLogsForFailedScenario("kvsharding.txt")
                .minimizeFailedScenario(true)
        )
    }
}