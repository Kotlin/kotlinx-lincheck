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
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import kotlin.random.Random

data class LogNumber(val term: Int, val index: Int)
data class KVEntry(val key: String?, val value: String?)
data class ReqId(val client: Int, val opId: Int)

sealed class RKVData

data class RKVLog(
    val entry: KVEntry,
    val number: LogNumber,
    val reqId: ReqId,
    var committed: Boolean = false
) : RKVData()

data class RKVVote(var votedFor: Int?) : RKVData()
data class RKVTerm(var term: Int) : RKVData()
data class RKVOpId(var id: Int) : RKVData()

sealed class RKVMessage {
    abstract val term: Int
}

data class RKVVoteRequest(override val term: Int, val number: LogNumber?) : RKVMessage()

data class RKVVoteResponse(override val term: Int, val res: Boolean) : RKVMessage()

data class RKVPing(override val term: Int, val opId: Int) : RKVMessage()

data class RKVPong(override val term: Int, val opId: Int) : RKVMessage()

data class RKVHeartbeat(override val term: Int, val lastCommitted: LogNumber?) : RKVMessage()

data class RKVPutRequest(override val term: Int, val opId: Int, val kv: KVEntry) : RKVMessage()

data class RKVPutResponse(override val term: Int, val opId: Int) : RKVMessage()

data class RKVApplyEntryRequest(
    val logNumber: LogNumber,
    val entry: KVEntry,
    val requestId: ReqId,
    val prevEntry: LogNumber?,
    override val term: Int,
    val lastCommitted: LogNumber?
) : RKVMessage()

data class RKVApplyEntryResponse(val logNumber: LogNumber, override val term: Int) : RKVMessage()
data class RKVMissingEntryResponse(val logNumber: LogNumber, override val term: Int) : RKVMessage()

class LogStorage(val log: MutableList<RKVData>) {
    var opIdIndex = log.indexOfLast { it is RKVOpId }
    var votedForIndex = log.indexOfLast { it is RKVVote }
    var termIndex = log.indexOfLast { it is RKVTerm }

    fun getOpId() = if (opIdIndex == -1) {
        -1
    } else {
        (log[opIdIndex] as RKVOpId).id
    }

    fun incOpId() = if (opIdIndex == -1) {
        opIdIndex = log.size
        log.add(RKVOpId(0))
        0
    } else {
        ++(log[opIdIndex] as RKVOpId).id
    }

    fun getVotedFor() = if (votedForIndex == -1) {
        null
    } else {
        (log[votedForIndex] as RKVVote).votedFor
    }

    fun updateVotedFor(vote: Int?) {
        if (votedForIndex == -1) {
            votedForIndex = log.size
            log.add(RKVVote(vote))
        } else {
            (log[votedForIndex] as RKVVote).votedFor = vote
        }
    }

    fun getTerm() = if (termIndex == -1) {
        0
    } else {
        (log[termIndex] as RKVTerm).term
    }

    fun updateTerm(term: Int) {
        if (termIndex == -1) {
            termIndex = log.size
            log.add(RKVTerm(term))
        } else {
            (log[termIndex] as RKVTerm).term = term
        }
    }

    fun getLastNumber() = log.filterIsInstance<RKVLog>().lastOrNull()?.number

    fun getPrevEntry(logNumber: LogNumber): LogNumber? {
        val logs = log.filterIsInstance<RKVLog>()
        val index = logs.indexOfLast { it.number == logNumber }
        check(index >= 0)
        return if (index == 0) {
            null
        } else {
            logs[index - 1].number
        }
    }

    fun getLastCommittedEntry() = log.filterIsInstance<RKVLog>().lastOrNull { it.committed }?.number

    fun commit(logNumber: LogNumber): ReqId? {
        val logs = log.filterIsInstance<RKVLog>()
        val index = logs.indexOfLast { it.number == logNumber }
        if (index == -1) return null
        for (i in 0..index) {
            logs[i].committed = true
        }
        return logs[index].reqId
    }

    fun contains(logNumber: LogNumber) = log.any { it is RKVLog && it.number == logNumber }

    fun add(request: RKVApplyEntryRequest) {
        check(!contains(request.logNumber)) {
            request
        }
        log.add(RKVLog(request.entry, request.logNumber, request.requestId))
    }

    fun add(entry: KVEntry, reqId: ReqId, term: Int): Pair<LogNumber?, Boolean> {
        val logs = log.filterIsInstance<RKVLog>()
        val indexOfEntry = logs.indexOfLast { it.entry.key != null && it.reqId == reqId }
        return if (indexOfEntry == -1) {
            val index = logs.size
            val number = LogNumber(index = index, term = term)
            val newEntry = RKVLog(entry, number, reqId)
            log.add(newEntry)
            number to false
        } else {
            null to logs[indexOfEntry].committed
        }
    }

    fun getKV(logNumber: LogNumber): RKVLog {
        return log.last { it is RKVLog && it.number == logNumber } as RKVLog
    }

    fun clearBefore(logNumber: LogNumber?) {
        val logs = log.filterIsInstance<RKVLog>()
        val toRemove = logs.takeWhile { it.number != logNumber }
        log.removeIf { it in toRemove }
    }
}


class Raft(val env: Environment<RKVMessage, RKVData>) : Node<RKVMessage> {
    companion object {
        const val HEARTBEAT_RATE = 50
        const val MISSED_HEARTBEAT_LIMIT = 5
    }

    private val storage = LogStorage(env.log)

    private var term = 0
    private var missedHeartbeat = 0
    private var leader: Int? = null
    private var status = NodeStatus.FOLLOWER
    private val random = Random(env.nodeId)
    private var votedFor: Int? = null
    private val electionSignal = Signal()
    private var receivedOks = 0
    private val quorum = (env.numberOfNodes + 1) / 2
    private var operationSignal = Signal()
    private var hasResponse = false
    private var sleepSignal = Signal()
    private var opId = -1
    private val responseCounts = mutableMapOf<LogNumber, Int>()
    private val missedEntries = mutableListOf<RKVApplyEntryRequest>()

    private suspend fun checkTimer() {
        if (leader != env.nodeId) {
            missedHeartbeat++
            if (missedHeartbeat == MISSED_HEARTBEAT_LIMIT) {
                startElection()
            }
        }
    }

    private fun onPutRequest(msg: RKVPutRequest, sender: Int) {
        check(status == NodeStatus.LEADER)
        val reqId = ReqId(client = sender, opId = msg.opId)
        val res = storage.add(msg.kv, reqId, term)
        if (res.first == null) {
            if (!res.second) return
            env.send(RKVPutResponse(term, msg.opId), sender)
        } else {
            val prevEntry = storage.getPrevEntry(res.first!!)
            responseCounts[res.first!!] = 1
            env.broadcast(
                RKVApplyEntryRequest(
                    res.first!!,
                    msg.kv,
                    reqId,
                    prevEntry,
                    term,
                    storage.getLastCommittedEntry()
                )
            )
        }
    }

    private fun onApplyEntriesResponse(msg: RKVApplyEntryResponse, sender: Int) {
        check(status == NodeStatus.LEADER)
        if (!responseCounts.contains(msg.logNumber)) return
        responseCounts[msg.logNumber] = responseCounts[msg.logNumber]!! + 1
        //println("In response ${responseCounts[msg.logNumber]}")
        if (responseCounts[msg.logNumber]!! < quorum) return
        val reqId = storage.commit(msg.logNumber)!!
        env.send(RKVPutResponse(term = term, opId = reqId.opId), reqId.client)
        //println("Last committed entry ${storage.getLastCommittedEntry()}")
        env.broadcast(RKVHeartbeat(term, storage.getLastCommittedEntry()))
    }

    private fun onMissingEntriesRequest(msg: RKVMissingEntryResponse, sender: Int) {
        check(status == NodeStatus.LEADER)
        val prevEntry = storage.getPrevEntry(msg.logNumber)
        val cur = storage.getKV(msg.logNumber)
        env.send(
            RKVApplyEntryRequest(
                msg.logNumber,
                cur.entry,
                cur.reqId,
                prevEntry,
                term,
                storage.getLastCommittedEntry()
            ), sender
        )
    }

    private fun replicateReq(key: String?, value: String?) {
        check(status == NodeStatus.LEADER)
        val reqId = ReqId(client = env.nodeId, opId = opId)
        val kv = KVEntry(key, value)
        val res = storage.add(kv, reqId, term)
        if (res.first == null) {
            if (!res.second) return
            /*hasResponse = true
            operationSignal.signal()*/
        } else {
            val prevEntry = storage.getPrevEntry(res.first!!)
            responseCounts[res.first!!] = 1
            env.broadcast(
                RKVApplyEntryRequest(
                    res.first!!,
                    kv,
                    reqId,
                    prevEntry,
                    term,
                    storage.getLastCommittedEntry()
                )
            )
        }
    }

    private fun onApplyEntryRequest(msg: RKVApplyEntryRequest) {
        if (msg.prevEntry == null || storage.contains(msg.prevEntry)) {
            storage.clearBefore(msg.prevEntry)
            for (e in missedEntries.reversed()) {
                storage.add(e)
            }
            storage.add(msg)
            missedEntries.clear()
            env.send(RKVApplyEntryResponse(msg.logNumber, term), leader!!)
        } else {
            missedEntries.add(msg)
            env.send(RKVMissingEntryResponse(msg.prevEntry, term), leader!!)
        }
        if (msg.lastCommitted != null) storage.commit(msg.lastCommitted)
    }

    private fun onHeartbeat(lastCommitted: LogNumber?) {
        if (lastCommitted != null && storage.commit(lastCommitted) == null) {
            env.send(RKVMissingEntryResponse(lastCommitted, term), leader!!)
        }
    }

    override fun onMessage(message: RKVMessage, sender: Int) {
        if (term > message.term) {
            env.recordInternalEvent("Ignore message with less term $message")
            return
        }
        when (status) {
            NodeStatus.LEADER -> {
                when (message) {
                    is RKVPing -> {
                        env.send(RKVPong(term, message.opId), sender)
                    }
                    is RKVHeartbeat -> {
                        check(term < message.term) {
                            "${env.nodeId}: $term, $message"
                        }
                        term = message.term
                        storage.updateTerm(term)
                        leader = sender
                        onAnotherLeaderElected()
                        onHeartbeat(message.lastCommitted)
                    }
                    is RKVVoteRequest -> {
                        val lastNumber = storage.getLastNumber()
                        if (lastNumber != null && (message.number == null || message.number.term < lastNumber.term ||
                                    message.number.index < lastNumber.index)
                        ) {
                            env.send(RKVVoteResponse(term, false), sender)
                            return
                        }
                        if (message.term == term) {
                            env.send(RKVHeartbeat(term, storage.getLastCommittedEntry()), sender)
                        } else {
                            leader = null
                            term = message.term
                            storage.updateTerm(term)
                            onAnotherLeaderElected()
                            votedFor = sender
                            storage.updateVotedFor(votedFor)
                            env.send(RKVVoteResponse(term, true), sender)
                        }
                    }
                    is RKVApplyEntryRequest -> {
                        check(term < message.term)
                        term = message.term
                        storage.updateTerm(term)
                        leader = sender
                        onAnotherLeaderElected()
                        onApplyEntryRequest(message)
                    }
                    is RKVMissingEntryResponse -> {
                        check(term == message.term)
                        onMissingEntriesRequest(message, sender)
                    }
                    is RKVApplyEntryResponse -> {
                        check(term == message.term)
                        onApplyEntriesResponse(message, sender)
                    }
                    is RKVPutRequest -> {
                        check(term == message.term)
                        onPutRequest(message, sender)
                    }
                    else -> env.recordInternalEvent("Ignore message $message from $sender")
                }
            }
            NodeStatus.CANDIDATE -> {
                when (message) {
                    is RKVHeartbeat -> {
                        term = message.term
                        leader = sender
                        storage.updateTerm(term)
                        onElectionFail()
                    }
                    is RKVApplyEntryRequest -> {
                        term = message.term
                        leader = sender
                        storage.updateTerm(term)
                        onElectionFail()
                        onApplyEntryRequest(message)
                    }
                    is RKVVoteRequest -> {
                        val lastNumber = storage.getLastNumber()
                        if (lastNumber != null && (message.number == null || message.number.term < lastNumber.term ||
                                    message.number.index < lastNumber.index)
                        ) {
                            env.send(RKVVoteResponse(term, false), sender)
                            return
                        }
                        if ((votedFor != null || votedFor != sender) && term == message.term) {
                            env.send(RKVVoteResponse(term, false), sender)
                        } else {
                            term = message.term
                            storage.updateTerm(term)
                            votedFor = sender
                            storage.updateVotedFor(votedFor)
                            env.send(RKVVoteResponse(term, true), sender)
                            onElectionFail()
                        }
                    }
                    is RKVVoteResponse -> {
                        if (message.term > term) {
                            term = message.term
                            storage.updateTerm(term)
                            onElectionFail()
                            return
                        }
                        if (!message.res || message.term != term) return
                        receivedOks++
                        if (receivedOks >= quorum) onElectionSuccess()
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
            NodeStatus.FOLLOWER -> {
                when (message) {
                    is RKVHeartbeat -> {
                        term = message.term
                        storage.updateTerm(term)
                        leader = sender
                        missedHeartbeat = 0
                        operationSignal.signal()
                        sleepSignal.signal()
                        onHeartbeat(message.lastCommitted)
                    }
                    is RKVVoteRequest -> {
                        val lastNumber = storage.getLastNumber()
                        if (lastNumber != null && (message.number == null || message.number.term < lastNumber.term ||
                                    message.number.index < lastNumber.index)
                        ) {
                            env.send(RKVVoteResponse(term, false), sender)
                            return
                        }
                        if (votedFor != null && term == message.term) {
                            env.send(RKVVoteResponse(term, false), sender)
                            return
                        }
                        votedFor = sender
                        storage.updateVotedFor(votedFor)
                        term = message.term
                        storage.updateTerm(term)
                        missedHeartbeat = 0
                        leader = null
                        env.send(RKVVoteResponse(term, true), sender)
                        sleepSignal.signal()
                    }
                    is RKVPong -> {
                        if (sender == leader && message.term == term && opId == message.opId) {
                            hasResponse = true
                            operationSignal.signal()
                        }
                    }
                    is RKVApplyEntryRequest -> {
                        term = message.term
                        storage.updateTerm(term)
                        leader = sender
                        missedHeartbeat = 0
                        onApplyEntryRequest(message)
                        operationSignal.signal()
                        sleepSignal.signal()
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
        }
    }

    override fun onStart() {
        env.recordInternalEvent("Set check on start")
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
    }

    private fun onElectionFail() {
        if (status != NodeStatus.CANDIDATE) return
        status = NodeStatus.FOLLOWER
        env.recordInternalEvent("Set check in election fail")
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
        electionSignal.signal()
        receivedOks = 0
        sleepSignal.signal()
    }

    private fun onAnotherLeaderElected() {
        if (status != NodeStatus.LEADER) return
        status = NodeStatus.FOLLOWER
        env.cancelTimer("HEARTBEAT")
        env.recordInternalEvent("Set check in another leader elected")
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
    }

    private fun onElectionSuccess() {
        env.recordInternalEvent("Election success")
        env.setTimer("HEARTBEAT", HEARTBEAT_RATE) {
            env.broadcast(RKVHeartbeat(term, storage.getLastCommittedEntry()))
        }
        status = NodeStatus.LEADER
        leader = env.nodeId
        electionSignal.signal()
        receivedOks = 0
        replicateReq(null, null)
        operationSignal.signal()
        sleepSignal.signal()
    }

    override fun stateRepresentation(): String {
        val sb = StringBuilder()
        sb.append("{currentTerm=$term, ")
        sb.append("currentLeader=$leader, ")
        sb.append("status=$status, ")
        sb.append("missedHeartbeat=$missedHeartbeat, ")
        sb.append("receivedOks=$receivedOks, ")
        sb.append("votedFor=$votedFor, ")
        sb.append("missingEntries=$missedEntries, ")
        sb.append("logs=${env.log.filterIsInstance<RKVLog>()}")
        sb.append("}")
        return sb.toString()
    }

    private suspend fun startElection() {
        env.cancelTimer("CHECK")
        leader = null
        missedHeartbeat = 0
        env.recordInternalEvent("Start election")
        while (true) {
            val currentTerm = term
            val timeToSleep = random.nextInt(env.numberOfNodes * 100)
            env.recordInternalEvent("Time to sleep is $timeToSleep, saved term is $currentTerm")
            sleepSignal = Signal()
            val shouldQuit = env.withTimeout(timeToSleep) {
                sleepSignal.await()
            }
            if (shouldQuit) check(currentTerm != term || leader != null)
            if (currentTerm != term || leader != null) {
                if (leader != env.nodeId) {
                    env.recordInternalEvent("Set check after sleep prevTerm=$currentTerm")
                    try {
                        env.setTimer("CHECK", HEARTBEAT_RATE) {
                            checkTimer()
                        }
                    } catch (_: IllegalArgumentException) {
                    }
                }
                env.recordInternalEvent("Exit election")
                return
            }
            env.recordInternalEvent("Ready to send requests $currentTerm")
            // votedFor = null
            status = NodeStatus.CANDIDATE
            term++
            storage.updateTerm(term)
            receivedOks = 1
            votedFor = env.nodeId
            storage.updateVotedFor(votedFor)
            env.broadcast(RKVVoteRequest(term, storage.getLastNumber()))
            if (env.numberOfNodes != 1) {
                env.withTimeout(40) {
                    electionSignal.await()
                }
            } else {
                onElectionSuccess()
            }
            if (status != NodeStatus.CANDIDATE) {
                env.recordInternalEvent("Exit election from end")
                //operationSignal.signal()
                return
            }
        }
    }

    override fun recover() {
        term = storage.getTerm()
        votedFor = storage.getVotedFor()
        opId = storage.getOpId()
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun ping() {
        operationSignal = Signal()
        hasResponse = false
        opId = storage.incOpId()
        while (true) {
            while (leader == null) operationSignal.await()
            if (leader == null) {
                operationSignal = Signal()
                continue
            }
            if (leader == env.nodeId) return
            env.send(RKVPing(term, opId), leader!!)
            operationSignal = Signal()
            operationSignal.await()
            if (hasResponse) return
        }
    }

    override suspend fun onScenarioFinish() {
        env.recordInternalEvent("Operations over")
    }

    @Validate
    fun validate() {
        val t =
            env.events()[env.nodeId].filterIsInstance<InternalEvent>().filter { it.message == "Operations over" }.size
        check(t <= 1)
        //println(env.log.filterIsInstance<RKVLog>())
    }
}
