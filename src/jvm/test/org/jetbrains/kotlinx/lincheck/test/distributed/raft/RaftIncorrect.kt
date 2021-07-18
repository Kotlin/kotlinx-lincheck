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

package org.jetbrains.kotlinx.lincheck.test.distributed.raft

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.junit.Test
import java.lang.IllegalArgumentException
import java.lang.StringBuilder
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

enum class NodeStatus { LEADER, CANDIDATE, FOLLOWER }

sealed class VoteReason
object Ok : VoteReason()
class Refuse(val leader: Int)

data class LogEntryNumber(val term: Int, val index: Int)

sealed class Message {
    abstract val term: Int
}

data class RequestVote(override val term: Int, val count: Int, val candidate: Int) : Message()
data class Heartbeat(override val term: Int, val lastCommittedEntry: LogEntryNumber?) : Message()
data class VoteResponse(override val term: Int, val res: Boolean) : Message()
data class PutRequest(override val term: Int, val key: Int, val value: Int, val hash: String) : Message()
data class PutResponse(override val term: Int, val hash: String) : Message()
data class GetRequest(override val term: Int, val key: Int, val hash: String) : Message()
data class GetResponse(override val term: Int, val hash: String, val value: Int?) : Message()
data class ApplyEntryRequest(override val term: Int, val logEntry: Log, val prevLogNumber: LogEntryNumber?) : Message()
data class MissingEntryResponse(override val term: Int, val prevLogNumber: LogEntryNumber?) : Message()
data class ApplyEntryResponse(override val term: Int, val logNumber: LogEntryNumber) : Message()

data class Log(
    val key: Int,
    val value: Int,
    val term: Int,
    val sender: Int,
    val hash: String,
    var committed: Boolean = false
)

data class OperationPromise(val signal: Signal, var result: Message? = null)

class Storage(val log: MutableList<Log>) {
    fun add(entry: Log): Boolean {
        if (log.any { it.hash == entry.hash }) return false
        log.add(entry)
        return true
    }

    fun get(key: Int): Int? {
        return log.filter { it.committed }.lastOrNull { it.key == key }?.value
    }

    fun commit(entryNumber: LogEntryNumber): Boolean {
        if (entryNumber.index >= log.size || log[entryNumber.index].term != entryNumber.term) {
            return false
        }
        for (i in 0..entryNumber.index) {
            log[i].committed = true
        }
        return true
    }

    fun getLastCommittedEntry() =
        log.filter { it.committed }.mapIndexed { i, l -> LogEntryNumber(term = l.term, index = i) }.lastOrNull()

    fun getLastEntry() = log.mapIndexed { i, l -> LogEntryNumber(term = l.term, index = i) }.lastOrNull()

    fun prevEntry(logEntry: LogEntryNumber): LogEntryNumber? {
        check(log[logEntry.index].term == logEntry.term)
        return if (logEntry.index == 0) {
            null
        } else {
            LogEntryNumber(log[logEntry.index - 1].term, logEntry.index - 1)
        }
    }

    fun checkEntry(entryNumber: LogEntryNumber?): Boolean {
        if (entryNumber == null) return true
        return entryNumber.index < log.size && log[entryNumber.index].term == entryNumber.term
    }

    fun applyEntries(entries: List<Log>, lastIndex: Int) {
        log.dropLast(log.size - (lastIndex + 1))
        log.addAll(entries)
    }

    fun size() = log.size

    operator fun get(entryNumber: LogEntryNumber): Log {
        check(log[entryNumber.index].term == entryNumber.term)
        return log[entryNumber.index]
    }

    fun getLastTerm(): Int {
        return log.lastOrNull()?.term ?: 0
    }
}

class RaftServerIncorrect(val env: Environment<Message, Log>) : Node<Message> {
    companion object {
        const val HEARTBEAT_RATE = 20
        const val MISSED_HEARTBEATS_LIMIT = 5
    }

    private var currentTerm = 0
    private var currentLeader: Int? = null
    private var votedFor: Int? = null
    private var status: NodeStatus = NodeStatus.FOLLOWER
    private val electionSemaphore = Signal()
    private var receivedOks = 0
    private val quorum = env.numberOfNodes / 2 + 1
    private var missedHeartbeatCnt = 0
    private val logEntriesCommitResponses = mutableMapOf<LogEntryNumber, Int>()
    private val storage = Storage(env.log)
    private val recoveredEntries = mutableListOf<Log>()
    private var opId = 0
    private val operations = mutableMapOf<String, OperationPromise>()
    private var op: Continuation<Unit>? = null
    private val random = Random(env.nodeId)

    override fun onStart() {
        super.onStart()
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            if (status != NodeStatus.LEADER) {
                if (missedHeartbeatCnt < MISSED_HEARTBEATS_LIMIT && status != NodeStatus.CANDIDATE) missedHeartbeatCnt++
                if (missedHeartbeatCnt == MISSED_HEARTBEATS_LIMIT) {
                    startElection()
                }
            } else {
                missedHeartbeatCnt = 0
            }
        }
    }

    override fun stateRepresentation(): String {
        val sb = StringBuilder()
        sb.append("{currentTerm=$currentTerm, ")
        sb.append("currentLeader=$currentLeader, ")
        sb.append("logEntriesCommitResponses=")
        sb.append(logEntriesCommitResponses)
        sb.append(", ")
        sb.append("status=$status, ")
        sb.append("missedHeartbeat=$missedHeartbeatCnt, ")
        sb.append("votedFor=$votedFor, ")
        sb.append("log=")
        sb.append(env.log)
        sb.append("}")
        return sb.toString()
    }

    private fun replicateLog(log: Log) {
        val lastEntry = storage.getLastEntry()
        storage.add(log)
        val entry = storage.getLastEntry()!!
        logEntriesCommitResponses[entry] = 1
        env.broadcast(ApplyEntryRequest(currentTerm, log, lastEntry))
        if (env.numberOfNodes == 1) {
            storage.commit(entry)
            try {
                op?.resume(Unit)
            } catch(_: IllegalStateException) {

            }
        }
    }

    private fun onPutRequest(message: PutRequest, sender: Int) {
        check(status == NodeStatus.LEADER)
        if (env.log.any { it.hash == message.hash }) {
            if (env.log.any { it.hash == message.hash && it.committed }) {
                env.send(PutResponse(currentTerm, message.hash), sender)
            }
            return
        }
        val log = Log(
            key = message.key,
            value = message.value,
            term = currentTerm,
            sender = sender,
            hash = message.hash
        )
        replicateLog(log)
        return
    }

    override fun onMessage(message: Message, sender: Int) {
        if (message is GetRequest && status == NodeStatus.LEADER) {
            val value = storage.get(message.key)
            env.send(GetResponse(hash = message.hash, value = value, term = currentTerm), sender)
            return
        }
        if (message is PutRequest && status == NodeStatus.LEADER) {
            onPutRequest(message, sender)
        }
        if (currentTerm > message.term) return
        updateTerm(message, sender)
        when (message) {
            is RequestVote -> {
                if (votedFor == null &&
                    message.count >= storage.size()
                ) {
                    env.send(VoteResponse(currentTerm, true), sender)
                    votedFor = sender
                } else {
                    env.send(VoteResponse(currentTerm, false), sender)
                }
            }
            is VoteResponse -> {
                if (message.res) receivedOks++
                if (receivedOks >= quorum) {
                    currentLeader = env.nodeId
                    status = NodeStatus.LEADER
                    electionSemaphore.signal()
                }
            }
            is Heartbeat -> {
                missedHeartbeatCnt = 0
                votedFor = null
                status = NodeStatus.FOLLOWER
                currentLeader = sender
                updateCommittedEntries(message.lastCommittedEntry)
                electionSemaphore.signal()
                try {
                    op?.resume(Unit)
                } catch(_: IllegalStateException) {
                }
            }
            is ApplyEntryResponse -> processApplyEntryResponse(message, sender)
            is ApplyEntryRequest -> processApplyEntryRequest(message, sender)
            is MissingEntryResponse -> processMissingEntriesResponse(message, sender)
            is GetRequest -> return // If we're the leader, the request was already processed
            is PutRequest -> return // If we're the leader, the request was already processed
            is PutResponse -> {
                operations[message.hash]?.result = message
                operations[message.hash]?.signal?.signal()
            }
            is GetResponse -> {
                operations[message.hash]?.result = message
                operations[message.hash]?.signal?.signal()
            }
        }
    }

    private fun processApplyEntryRequest(message: ApplyEntryRequest, sender: Int) {
        if (currentLeader != sender) {
            return
        }
        recoveredEntries.add(message.logEntry.copy())
        if (storage.checkEntry(message.prevLogNumber)) {
            storage.applyEntries(recoveredEntries.asReversed(), message.prevLogNumber?.index ?: -1)
            recoveredEntries.clear()
            val entry = storage.getLastEntry()
            env.send(ApplyEntryResponse(currentTerm, entry!!), sender)
        } else {
            env.send(MissingEntryResponse(currentTerm, message.prevLogNumber), sender)
        }
    }

    private fun processApplyEntryResponse(message: ApplyEntryResponse, sender: Int) {
        if (status != NodeStatus.LEADER) {
            return
        }
        logEntriesCommitResponses[message.logNumber] = logEntriesCommitResponses.getOrPut(message.logNumber) { 0 } + 1
        env.recordInternalEvent("Map updated")
        if (logEntriesCommitResponses[message.logNumber]!! < quorum) {
            return
        }

        if (storage.commit(message.logNumber)) {
            env.send(
                PutResponse(
                    currentTerm,
                    storage[message.logNumber].hash
                ), storage[message.logNumber].sender
            )
        }
    }

    private fun processMissingEntriesResponse(message: MissingEntryResponse, sender: Int) {
        val prevEntry = storage.prevEntry(message.prevLogNumber!!)
        env.send(ApplyEntryRequest(currentTerm, storage[message.prevLogNumber], prevEntry), sender)
    }

    private fun updateCommittedEntries(lastCommittedEntry: LogEntryNumber?) {
        if (lastCommittedEntry == null) return
        if (!storage.commit(lastCommittedEntry)) {
            env.send(
                MissingEntryResponse(currentTerm, lastCommittedEntry),
                currentLeader!!
            )
        }
    }

    private fun onElectionSuccess() {
        currentLeader = env.nodeId
        env.setTimer("Heartbeat", HEARTBEAT_RATE) {
            env.recordInternalEvent("HI")
            env.broadcast(Heartbeat(currentTerm, storage.getLastCommittedEntry()))
            env.recordInternalEvent("BY")
        }
        op?.resume(Unit)
    }

    private fun updateTerm(message: Message, sender: Int) {
        if (message.term > currentTerm) {
            if (status == NodeStatus.LEADER) {
                try {
                    env.cancelTimer("Heartbeat")
                } catch (_: IllegalArgumentException) {
                }
            }
            currentTerm = message.term
            status = NodeStatus.FOLLOWER
            electionSemaphore.signal()
        }
    }

    override fun recover() {
        currentLeader = null
        currentTerm = storage.getLastTerm()
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            if (status != NodeStatus.LEADER) {
                if (missedHeartbeatCnt < MISSED_HEARTBEATS_LIMIT && status != NodeStatus.CANDIDATE) missedHeartbeatCnt++
                if (missedHeartbeatCnt == MISSED_HEARTBEATS_LIMIT) {
                    startElection()
                }
            } else {
                missedHeartbeatCnt = 0
            }
        }
    }

    private suspend fun startElection() {
        missedHeartbeatCnt = 0
        env.recordInternalEvent("Start election")
        currentLeader = null
        recoveredEntries.clear()
        status = NodeStatus.CANDIDATE
        while (true) {
            votedFor = null
            val timeToSleep = random.nextInt(env.numberOfNodes * 100)
            env.recordInternalEvent("Time to sleep is $timeToSleep")
            env.sleep(timeToSleep)
            if (status == NodeStatus.FOLLOWER || votedFor != null) return
            currentTerm++
            votedFor = env.nodeId
            receivedOks = 1
            val index = storage.size()
            env.broadcast(RequestVote(currentTerm, index, env.nodeId))
            if (env.numberOfNodes == 1) {
                status = NodeStatus.LEADER
            } else {
                env.withTimeout(10) {
                    electionSemaphore.await()
                }
            }
            if (status == NodeStatus.LEADER) {
                onElectionSuccess()
            }
            if (status != NodeStatus.CANDIDATE) {
                votedFor = null
                return
            }
        }
    }

    private fun constructHash(): String = "${hashCode()}#${env.nodeId}#$opId"

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: Int): Int? {
        if (currentLeader == null) suspendCoroutine<Unit> { continuation -> op = continuation }
        check(currentLeader != null)
        if (status == NodeStatus.LEADER) {
            return storage.get(key)
        }
        op = null
        opId++
        val hash = constructHash()
        val promise = OperationPromise(Signal())
        operations[hash] = promise
        while (true) {
            env.send(GetRequest(currentTerm, key, hash), currentLeader!!)
            env.withTimeout(25) {
                promise.signal.await()
            }
            if (promise.result != null) {
                return (promise.result as GetResponse).value
            }
            if (currentLeader == null) suspendCoroutine<Unit> { continuation -> op = continuation }
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: Int, value: Int) {
        if (currentLeader == null) suspendCoroutine<Unit> { continuation -> op = continuation }
        check(currentLeader != null)
        op = null
        opId++
        val hash = constructHash()
        val promise = OperationPromise(Signal())
        operations[hash] = promise
        while (true) {
            env.send(PutRequest(currentTerm, key, value, hash), currentLeader!!)
            env.withTimeout(50) {
                promise.signal.await()
            }
            if (promise.result != null) {
                return
            }
            if (currentLeader == null) suspendCoroutine<Unit> { continuation -> op = continuation }
        }
    }
}

class ReplicaSpecification {
    private val storage = mutableMapOf<Int, Int>()

    @Operation
    suspend fun get(key: Int) = storage[key]

    @Operation
    suspend fun put(key: Int, value: Int) {
        storage[key] = value
    }
}
