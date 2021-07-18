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
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.lang.StringBuilder
import kotlin.random.Random

sealed class RMessage {
    abstract val term: Int
}

data class RVoteRequest(override val term: Int) : RMessage()

data class RVoteResponse(override val term: Int, val res: Boolean) : RMessage()

data class RPing(override val term: Int) : RMessage()

data class RPong(override val term: Int) : RMessage()

data class RHeartbeat(override val term: Int) : RMessage()

class RaftConsensusIncorrect(val env: Environment<RMessage, Int>) : Node<RMessage> {
    companion object {
        const val HEARTBEAT_RATE = 50
        const val MISSED_HEARTBEAT_LIMIT = 5
    }

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

    private suspend fun checkTimer() {
        if (leader != env.nodeId) {
            missedHeartbeat++
            if (missedHeartbeat == MISSED_HEARTBEAT_LIMIT) {
                startElection()
            }
        }
    }

    override fun onMessage(message: RMessage, sender: Int) {
        if (term > message.term) return
        when (status) {
            NodeStatus.LEADER -> {
                when (message) {
                    is RPing -> {
                        env.send(RPong(term), sender)
                    }
                    is RHeartbeat -> {
                        check(term < message.term)
                        term = message.term
                        env.log.add(term)
                        leader = sender
                        onAnotherLeaderElected()
                    }
                    is RVoteRequest -> {
                        if (message.term == term) {
                            env.send(RHeartbeat(term), sender)
                        } else {
                            leader = null
                            term = message.term
                            env.log.add(term)
                            onAnotherLeaderElected()
                            env.send(RVoteResponse(term, true), sender)
                            votedFor = sender
                        }
                    }
                }
            }
            NodeStatus.CANDIDATE -> {
                when (message) {
                    is RHeartbeat -> {
                        term = message.term
                        env.log.add(term)
                        onElectionFail()
                    }
                    is RVoteRequest -> {
                        if ((votedFor != null || votedFor != sender) && term == message.term) {
                            env.send(RVoteResponse(term, false), sender)
                        } else {
                            term = message.term
                            env.log.add(term)
                            votedFor = sender
                            env.send(RVoteResponse(term, true), sender)
                            onElectionFail()
                        }
                    }
                    is RVoteResponse -> {
                        if (message.term > term) {
                            term = message.term
                            env.log.add(term)
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
                        env.log.add(term)
                        leader = sender
                        missedHeartbeat = 0
                        operationSignal.signal()
                    }
                    is RVoteRequest -> {
                        if (votedFor != null && term == message.term) {
                            env.send(RVoteResponse(term, false), sender)
                            return
                        }
                        votedFor = sender
                        term = message.term
                        env.log.add(term)
                        missedHeartbeat = 0
                        leader = null
                        env.send(RVoteResponse(term, true), sender)
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
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
    }

    private fun onElectionFail() {
        if (status != NodeStatus.CANDIDATE) return
        status = NodeStatus.FOLLOWER
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
        electionSignal.signal()
        receivedOks = 0
    }

    private fun onAnotherLeaderElected() {
        if (status != NodeStatus.LEADER) return
        status = NodeStatus.FOLLOWER
        env.cancelTimer("HEARTBEAT")
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
            val timeToSleep = random.nextInt(env.numberOfNodes * 100)
            env.recordInternalEvent("Time to sleep is $timeToSleep")
            env.sleep(timeToSleep)
            if (currentTerm != term || leader != null) {
                if (leader != env.nodeId) {
                    env.setTimer("CHECK", HEARTBEAT_RATE) {
                        checkTimer()
                    }
                }
                return
            }
            env.recordInternalEvent("Ready to send requests")
            votedFor = null
            status = NodeStatus.CANDIDATE
            term++
            env.log.add(term)
            receivedOks = 1
            votedFor = env.nodeId
            env.broadcast(RVoteRequest(term))
            if (env.numberOfNodes != 1) {
                env.withTimeout(20) {
                    electionSignal.await()
                }
            } else {
                onElectionSuccess()
            }
            if (status != NodeStatus.CANDIDATE) {
                operationSignal.signal()
                return
            }
        }
    }

    override fun recover() {
        term = env.log.lastOrNull() ?: 0
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
            if (leader == env.nodeId) return
            env.send(RPing(term), leader!!)
            operationSignal = Signal()
            operationSignal.await()
            if (hasResponse) return
        }
    }

    override suspend fun onScenarioFinish() {
        env.recordInternalEvent("Operations over")
    }
}