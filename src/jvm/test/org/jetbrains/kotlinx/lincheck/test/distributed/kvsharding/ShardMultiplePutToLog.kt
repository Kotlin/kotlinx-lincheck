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

package org.jetbrains.kotlinx.lincheck.test.distributed.kvsharding

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node

sealed class LogEntry
data class KVLogEntry(val key: String, val value: String) : LogEntry()
data class OpIdEntry(val id: Int) : LogEntry()

class ShardMultiplePutToLog(val env: Environment<KVMessage, LogEntry>) : Node<KVMessage> {
    private var opId = 0
    private val semaphore = Semaphore(1, 1)
    private var response: KVMessage? = null
    private var delegate: Int? = null
    private var appliedOperations = Array(env.numberOfNodes) {
        mutableMapOf<Int, KVMessage>()
    }

    private fun getNodeForKey(key: String) =
        ((key.hashCode() % env.numberOfNodes) + env.numberOfNodes) % env.numberOfNodes

    private fun saveToLog(key: String, value: String): String? {
        val log = env.log
        val index = log.indexOfFirst { it is KVLogEntry && it.key == key }
        return if (index == -1) {
            log.add(KVLogEntry(key, value))
            null
        } else {
            val res = (log[index] as KVLogEntry).value
            log[index] = KVLogEntry(key, value)
            res
        }
    }

    private fun getFromLog(key: String): String? {
        val log = env.log
        val index = log.indexOfFirst { it is KVLogEntry && it.key == key }
        return if (index == -1) {
            null
        } else {
            (log[index] as KVLogEntry).value
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: String, value: String): String? {
        env.log.add(OpIdEntry(++opId))
        val node = getNodeForKey(key)
        if (node == env.nodeId) {
            return saveToLog(key, value)
        }
        val request = PutRequest(key, value, opId)
        return (send(request, node) as PutResponse).previousValue
    }

    private suspend fun send(request: KVMessage, receiver: Int): KVMessage {
        response = null
        delegate = receiver
        while (true) {
            env.send(request, receiver)
            semaphore.acquire()
            response ?: continue
            delegate = null
            return response!!
        }
    }

    override fun recover() {
        val id = env.log.filterIsInstance(OpIdEntry::class.java).lastOrNull()?.id ?: -1
        opId = id + 1
        env.broadcast(Recover)
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: String): String? {
        env.log.add(OpIdEntry(++opId))
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
                semaphore.release()
            }
            return
        }
        if (!message.isRequest) {
            if (message.id != opId) return
            if (response == null) {
                response = message
                semaphore.release()
            }
            return
        }
        // If the node has failed, applied operations are lost, and we can
        // put the same entry to log multiple times. This leads to incorrect results,
        // as 'put' returns previous value.
        val msg = appliedOperations[sender][message.id] ?: when (message) {
            is GetRequest -> GetResponse(getFromLog(message.key), message.id)
            is PutRequest -> PutResponse(saveToLog(message.key, message.value), message.id)
            else -> throw IllegalStateException()
        }
        appliedOperations[sender][message.id] = msg
        env.send(msg, sender)
    }
}