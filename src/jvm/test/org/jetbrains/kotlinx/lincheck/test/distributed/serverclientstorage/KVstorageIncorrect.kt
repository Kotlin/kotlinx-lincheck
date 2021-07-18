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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import java.util.*


class KVStorageServerIncorrect(private val env: Environment<Command, Unit>) : Node<Command> {
    private val storage = HashMap<Int, Int>()
    private val commandResults = Array<HashMap<Int, Command>>(env.numberOfNodes) {
        HashMap()
    }

    override fun onMessage(message: Command, sender: Int) {
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            env.send(commandResults[sender][id]!!, sender)
            return
        }
        val result: Command = try {
            when (message) {
                is ContainsCommand -> ContainsResult(storage.containsKey(message.key), id)
                is GetCommand -> GetResult(storage[message.key], id)
                is PutCommand -> PutResult(storage.put(message.key, message.value), id)
                is RemoveCommand -> RemoveResult(storage.remove(message.key), id)
                is AddCommand -> AddResult(
                    storage.put(
                        message.key,
                        storage.getOrDefault(message.key, 0) + message.value
                    ), id
                )
                else -> throw RuntimeException("Unexpected command")
            }
        } catch (e: Throwable) {
            ErrorResult(e, id)
        }
        env.send(result, receiver = sender)
    }
}


class KVStorageClientIncorrect(private val environment: Environment<Command, Unit>) : Node<Command> {
    private var commandId = 0
    private val commandResults = HashMap<Int, Command>()
    private val serverAddr = environment.getAddressesForClass(KVStorageServerIncorrect::class.java)!![0]
    private val signal = Signal()
    private val queue = LinkedList<Command>()


    private suspend fun sendOnce(command: Command): Command {
        while (true) {
            environment.send(command, serverAddr)
            environment.withTimeout(1) {
                signal.await()
            }
            val response = queue.poll() ?: continue
            commandResults[response.id] = response
            val res = commandResults[command.id] ?: continue
            if (res is ErrorResult) {
                throw res.error
            }
            return res
        }
    }

    @Operation
    suspend fun contains(key: Int): Boolean {
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation
    suspend fun put(key: Int, value: Int): Int? {
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation
    suspend fun remove(key: Int): Int? {
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation
    suspend fun get(key: Int): Int? {
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }

    @Operation
    suspend fun add(key: Int, value: Int): Int? {
        val response = sendOnce(AddCommand(key, value, commandId++)) as AddResult
        return response.res
    }

    override fun onMessage(message: Command, sender: Int) {
        queue.add(message)
        signal.signal()
    }
}
