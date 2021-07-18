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

sealed class RLog
data class RTerm(val term: Int) : RLog()
data class RVote(val votedFor: Int?) : RLog()

class RaftConsensus(val env: Environment<RMessage, RLog>) : Node<RMessage> {
    companion object {
        const val HEARTBEAT_RATE = 30
        const val MISSED_HEARTBEAT_LIMIT = 5
    }

    private var term = 0
    private var missedHeartbeat = 0
    private var leader: Int? = 0
    private var status = if (env.nodeId != leader) NodeStatus.FOLLOWER else NodeStatus.LEADER
    private val random = Random(env.nodeId)
    private var votedFor: Int? = null
    private val electionSignal = Signal()
    private var receivedOks = 0
    private val quorum = (env.numberOfNodes + 1) / 2
    private var operationSignal = Signal()
    private var hasResponse = false
    private var sleepSignal = Signal()

    private suspend fun checkTimer() {
        if (leader != env.nodeId) {
            missedHeartbeat++
            if (missedHeartbeat == MISSED_HEARTBEAT_LIMIT) {
                startElection()
            }
        }
    }

    override fun onMessage(message: RMessage, sender: Int) {
        if (term > message.term) {
            env.recordInternalEvent("Ignore message with less term $message")
            return
        }
        when (status) {
            NodeStatus.LEADER -> {
                when (message) {
                    is RPing -> {
                        env.send(RPong(term), sender)
                    }
                    is RHeartbeat -> {
                        check(term < message.term)
                        term = message.term
                        env.log.add(RTerm(term))
                        leader = sender
                        onAnotherLeaderElected()
                    }
                    is RVoteRequest -> {
                        if (message.term == term) {
                            env.send(RHeartbeat(term), sender)
                        } else {
                            leader = null
                            term = message.term
                            env.log.add(RTerm(term))
                            onAnotherLeaderElected()
                            votedFor = sender
                            env.log.add(RVote(votedFor))
                            env.send(RVoteResponse(term, true), sender)
                        }
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
            NodeStatus.CANDIDATE -> {
                when (message) {
                    is RHeartbeat -> {
                        term = message.term
                        leader = sender
                        env.log.add(RTerm(term))
                        onElectionFail()
                    }
                    is RVoteRequest -> {
                        if ((votedFor != null || votedFor != sender) && term == message.term) {
                            env.send(RVoteResponse(term, false), sender)
                        } else {
                            term = message.term
                            env.log.add(RTerm(term))
                            votedFor = sender
                            env.log.add(RVote(votedFor))
                            env.send(RVoteResponse(term, true), sender)
                            onElectionFail()
                        }
                    }
                    is RVoteResponse -> {
                        if (message.term > term) {
                            term = message.term
                            env.log.add(RTerm(term))
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
                    is RHeartbeat -> {
                        term = message.term
                        env.log.add(RTerm(term))
                        leader = sender
                        missedHeartbeat = 0
                        operationSignal.signal()
                        sleepSignal.signal()
                    }
                    is RVoteRequest -> {
                        if (votedFor != null && term == message.term) {
                            env.send(RVoteResponse(term, false), sender)
                            return
                        }
                        votedFor = sender
                        env.log.add(RVote(votedFor))
                        term = message.term
                        env.log.add(RTerm(term))
                        missedHeartbeat = 0
                        leader = null
                        env.send(RVoteResponse(term, true), sender)
                        sleepSignal.signal()
                    }
                    is RPong -> {
                        if (sender == leader && message.term == term) {
                            hasResponse = true
                            operationSignal.signal()
                        }
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
        }
    }

    override fun onStart() {
        env.recordInternalEvent("Set check on start")
        if (status == NodeStatus.FOLLOWER) env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        } else {
            env.setTimer("HEARTBEAT", HEARTBEAT_RATE) {
                env.broadcast(RHeartbeat(term))
            }
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
            env.broadcast(RHeartbeat(term))
        }
        status = NodeStatus.LEADER
        leader = env.nodeId
        electionSignal.signal()
        receivedOks = 0
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
        sb.append("votedFor=$votedFor")
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
            val timeToSleep = random.nextInt(env.numberOfNodes * 10)
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
            env.log.add(RTerm(term))
            receivedOks = 1
            votedFor = env.nodeId
            env.log.add(RVote(votedFor))
            env.broadcast(RVoteRequest(term))
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
        leader = null
        status = NodeStatus.FOLLOWER
        term = env.log.filterIsInstance<RTerm>().lastOrNull()?.term ?: 0
        votedFor = env.log.filterIsInstance<RVote>().lastOrNull()?.votedFor
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun ping() {
        operationSignal = Signal()
        hasResponse = false
        while (true) {
            while (leader == null) operationSignal.await()
            if (leader == null) {
                operationSignal = Signal()
                continue
            }
            if (leader == env.nodeId) {
                env.recordInternalEvent("Get result")
                return
            }
            env.send(RPing(term), leader!!)
            operationSignal = Signal()
            operationSignal.await()
            if (hasResponse) {
                env.recordInternalEvent("Get result")
                return
            }
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
    }
}
