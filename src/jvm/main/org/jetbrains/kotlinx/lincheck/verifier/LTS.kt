/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

package org.jetbrains.kotlinx.lincheck.verifier

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellableContinuationHolder.storedLastCancellableCont
import org.jetbrains.kotlinx.lincheck.verifier.LTS.*
import org.jetbrains.kotlinx.lincheck.verifier.OperationType.*
import java.util.*
import kotlin.collections.HashMap
import kotlin.coroutines.*
import kotlin.math.*

typealias RemappingFunction = IntArray
typealias ResumedTickets = Set<Int>

/**
 * Common interface for different labeled transition systems, which several correctness formalisms use.
 * Lincheck widely uses LTS-based formalisms for verification, see [AbstractLTSVerifier] implementations as examples.
 * Essentially, LTS provides an information of the possibility to do a transition from one state to another
 * by the specified operation with the specified result.
 *
 * In order not to construct the full LTS (which is impossible because it can be either infinite
 * or just too big to build), we construct it lazily during the requests and reuse it between runs.
 *
 * An [LTS] transition from the specified state by the specified operation
 * determines the possible result and the next state uniquely. Internally, we
 * represent transitions as [[State] x [Actor] x [TransitionInfo]].
 *
 * The current implementation supports transitions by partial operations that may register their request and
 * block it's execution waiting for some precondition to become true. Partial operations are presented in
 * "Nonblocking concurrent objects with condition synchronization" paper by Scherer III W., Scott M.
 *
 * Practically, Kotlin implementation of such operations via suspend functions is supported.
 */

class LTS(sequentialSpecification: Class<*>) {
    // we should transform the specification with `CancellabilitySupportClassTransformer`
    private val sequentialSpecification: Class<*> = TransformationClassLoader { cv -> CancellabilitySupportClassTransformer(cv)}
        .loadClass(sequentialSpecification.name)!!

    /**
     * Cache with all LTS states in order to reuse the equivalent ones.
     * Equivalency relation among LTS states is defined by the [StateInfo] class.
     */
    private val stateInfos = HashMap<StateInfo, StateInfo>()

    val initialState: State = createInitialState()

    /**
     * [LTS] state is defined by the sequence of operations that lead to this state from the [initialState].
     * Every state stores possible transitions([transitionsByRequests] and [transitionsByFollowUps]) by actors which are computed lazily
     * by the corresponding [next] requests ([nextByRequest] and [nextByFollowUp] respectively).
     */
    inner class State(val seqToCreate: List<Operation>) {
        internal val transitionsByRequests by lazy { mutableMapOf<Actor, TransitionInfo>() }
        internal val transitionsByFollowUps by lazy { mutableMapOf<Int, TransitionInfo>() }
        internal val transitionsByCancellations by lazy { mutableMapOf<Int, TransitionInfo>() }
        private val atomicallySuspendedAndCancelledTransition: TransitionInfo by lazy {
            createAtomicallySuspendedAndCancelledTransition()
        }

        /**
         * Computes or gets the existing transition from the current state by the given [actor].
         */
        fun next(actor: Actor, expectedResult: Result, ticket: Int) = when(ticket) {
            NO_TICKET -> nextByRequest(actor, expectedResult)
            else -> nextByFollowUp(actor, ticket, expectedResult)
        }

        private fun createAtomicallySuspendedAndCancelledTransition() =  copyAndApply { instance, suspendedOperations, resumedTicketsWithResults, continuationsMap ->
            TransitionInfo(this, getResumedOperations(resumedTicketsWithResults).map { it.resumedActorTicket }.toSet(), NO_TICKET, null, Cancelled)
        }

        private fun nextByRequest(actor: Actor, expectedResult: Result): TransitionInfo? {
            // Compute the transition following the sequential specification.
            val transitionInfo = transitionsByRequests.computeIfAbsent(actor) { a ->
                copyAndApply { instance, suspendedOperations, resumedTicketsWithResults, continuationsMap ->
                    val ticket = findFirstAvailableTicket(suspendedOperations, resumedTicketsWithResults)
                    val op = Operation(a, ticket, REQUEST)
                    // Invoke the given operation to count the next transition.
                    val result = op.invoke(instance, suspendedOperations, resumedTicketsWithResults, continuationsMap)
                    createTransition(op, result, instance, suspendedOperations, getResumedOperations(resumedTicketsWithResults))
                }
            }
            // Check whether the current actor allows an extra suspension and the the expected result is `Cancelled` while the
            // constructed transition does not suspend -- we can simply consider that this cancelled invocation does not take
            // any effect and remove it from the history.
            if (actor.allowExtraSuspension && expectedResult == Cancelled && transitionInfo.result != Suspended)
                return atomicallySuspendedAndCancelledTransition
            return if (expectedResult.isLegalByRequest(transitionInfo, actor.allowExtraSuspension)) transitionInfo else null
        }

        private fun nextByFollowUp(actor: Actor, ticket: Int, expectedResult: Result): TransitionInfo? {
            val transitionInfo = transitionsByFollowUps.computeIfAbsent(ticket) {
                copyAndApply { instance, suspendedOperations, resumedTicketsWithResults, continuationsMap ->
                    // Invoke the given operation to count the next transition.
                    val op = Operation(actor, ticket, FOLLOW_UP)
                    val result = op.invoke(instance, suspendedOperations, resumedTicketsWithResults, continuationsMap)
                    createTransition(op, result, instance, suspendedOperations, getResumedOperations(resumedTicketsWithResults))
                }
            }
            check(transitionInfo.result != Suspended) {
                "Execution of the follow-up part of this operation ${actor.method} suspended - this behaviour is not supported"
            }
            return if (expectedResult.isLegalByFollowUp(transitionInfo, actor.allowExtraSuspension)) transitionInfo else null
        }

        fun nextByCancellation(actor: Actor, ticket: Int): TransitionInfo = transitionsByCancellations.computeIfAbsent(ticket) {
            copyAndApply { instance, suspendedOperations, resumedTicketsWithResults, continuationsMap ->
                // Invoke the given operation to count the next transition.
                val op = Operation(actor, ticket, CANCELLATION)
                val result = op.invoke(instance, suspendedOperations, resumedTicketsWithResults, continuationsMap)
                check(result === Cancelled)
                createTransition(op, result, instance, suspendedOperations, getResumedOperations(resumedTicketsWithResults))
            }
        }

        private fun Result.isLegalByRequest(transitionInfo: TransitionInfo, allowExtraSuspension: Boolean) =
            isLegalByFollowUp(transitionInfo, allowExtraSuspension) ||
                    this.wasSuspended && (transitionInfo.result == Suspended || allowExtraSuspension) ||
                    !this.wasSuspended && transitionInfo.result == Suspended

        private fun Result.isLegalByFollowUp(transitionInfo: TransitionInfo, allowExtraSuspension: Boolean) =
            this == transitionInfo.result ||
                    this is ValueResult && transitionInfo.result is ValueResult && this.value == transitionInfo.result.value &&
                    (!wasSuspended && transitionInfo.result.wasSuspended || wasSuspended && allowExtraSuspension) ||
                    this is ExceptionResult && transitionInfo.result is ExceptionResult && this.tClazz == transitionInfo.result.tClazz &&
                    (!wasSuspended && transitionInfo.result.wasSuspended || wasSuspended && allowExtraSuspension) ||
                    this == VoidResult && transitionInfo.result == SuspendedVoidResult ||
                    this == SuspendedVoidResult && transitionInfo.result == VoidResult && allowExtraSuspension ||
                    this == CrashResult


        private inline fun <T> copyAndApply(
            action: (
                instance: Any,
                suspendedOperations: MutableList<Operation>,
                resumedTicketsWithResults: MutableMap<Int, ResumedResult>,
                continuationsMap: MutableMap<Operation, CancellableContinuation<*>>
            ) -> T
        ): T {
            // Copy the state by sequentially applying operations from seqToCreate.
            val instance = createInitialStateInstance()
            val suspendedOperations = mutableListOf<Operation>()
            val resumedTicketsWithResults = mutableMapOf<Int, ResumedResult>()
            val continuationsMap = mutableMapOf<Operation, CancellableContinuation<*>>()
            try {
                seqToCreate.forEach { it.invoke(instance, suspendedOperations, resumedTicketsWithResults, continuationsMap) }
            } catch (e: Exception) {
                throw  IllegalStateException(e)
            }
            return action(instance, suspendedOperations, resumedTicketsWithResults, continuationsMap)
        }

        private fun createTransition(
            actorWithTicket: Operation,
            result: Result,
            instance: Any,
            suspendedActorWithTickets: List<Operation>,
            resumedOperations: List<ResumptionInfo>
        ): TransitionInfo {
            val stateInfo = StateInfo(this, instance, suspendedActorWithTickets, resumedOperations)
            return stateInfo.intern(actorWithTicket) { nextStateInfo, rf ->
                TransitionInfo(
                    nextState = nextStateInfo.state,
                    resumedTickets = stateInfo.resumedOperations.map { it.resumedActorTicket }.toSet(),
                    ticket = if (rf != null && result === Suspended) rf[actorWithTicket.ticket] else actorWithTicket.ticket,
                    rf = rf,
                    result = result
                )
            }
        }

        private fun getResumedOperations(resumedTicketsWithResults: Map<Int, ResumedResult>): List<ResumptionInfo> {
            val resumedOperations = mutableListOf<ResumptionInfo>()
            resumedTicketsWithResults.forEach { resumedTicket, res ->
                resumedOperations.add(ResumptionInfo(res.resumedActor, res.by, resumedTicket))
            }
            // Ignore the order of resumption by sorting the list of resumptions.
            return resumedOperations.sortedBy { it.resumedActorTicket }
        }
    }

    private fun Operation.invoke(
        externalState: Any,
        suspendedOperations: MutableList<Operation>,
        resumedOperations: MutableMap<Int, ResumedResult>,
        continuationsMap: MutableMap<Operation, CancellableContinuation<*>>
    ): Result {
        val prevResumedTickets = resumedOperations.keys.toMutableList()
        storedLastCancellableCont = null
        val res = when (type) {
            REQUEST -> executeActor(externalState, actor, Completion(ticket, actor, resumedOperations))
            FOLLOW_UP -> {
                val (cont, suspensionPointRes) = resumedOperations[ticket]!!.contWithSuspensionPointRes
                val finalRes = (
                        if (cont == null) suspensionPointRes // Resumed operation has no follow-up.
                        else {
                            cont.resumeWith(suspensionPointRes)
                            resumedOperations[ticket]!!.contWithSuspensionPointRes.second
                        })
                resumedOperations.remove(ticket)
                createLincheckResult(finalRes, wasSuspended = true)
            }
            CANCELLATION -> {
                continuationsMap[Operation(this.actor, this.ticket, REQUEST)]!!.cancelByLincheck(promptCancellation = actor.promptCancellation)
                val wasSuspended = suspendedOperations.removeIf { it.actor == actor && it.ticket == ticket }
                if (!actor.promptCancellation) {
                    check(wasSuspended) { "The operation can be cancelled after resumption only in the prompt cancellation mode" }
                }
                if (resumedOperations.remove(ticket) != null) check(actor.promptCancellation) {
                    "The operation can be resumed and then cancelled only with the prompt cancellation enabled"
                }
                Cancelled
            }
        }
        if (res === Suspended) {
            val cont = storedLastCancellableCont
            storedLastCancellableCont = null
            if (cont !== null) continuationsMap[this] = cont
            // Operation suspended it's execution.
            suspendedOperations.add(this)
        }
        resumedOperations.forEach { (resumedTicket, res) ->
            if (!prevResumedTickets.contains(resumedTicket)) {
                suspendedOperations.removeIf { it.ticket == resumedTicket }
                res.by = actor
            }
        }
        return res
    }

    /**
     * Creates and stores the new LTS state or gets the one if already exists.
     */
    private inline fun <T> StateInfo.intern(
        curOperation: Operation?,
        block: (StateInfo, RemappingFunction?) -> T
    ): T {
        return if (stateInfos.containsKey(this)) {
            val old = stateInfos[this]!!
            block(old, this.computeRemappingFunction(old))
        } else {
            val newSeqToCreate = if (curOperation != null) this.state.seqToCreate + curOperation else emptyList()
            stateInfos[this] = this.also { it.state = State(newSeqToCreate) }
            return block(stateInfos[this]!!, null)
        }
    }

    private fun createInitialState(): State {
        val instance = createInitialStateInstance()
        val initialState = State(emptyList())
        return StateInfo(
            state = initialState,
            instance = instance,
            suspendedOperations = emptyList(),
            resumedOperations = emptyList()
        ).intern(null) { _, _ -> initialState }
    }

    private fun createInitialStateInstance() = sequentialSpecification.newInstance()

    fun checkStateEquivalenceImplementation(): Boolean {
        val i1 = createInitialStateInstance()
        val i2 = createInitialStateInstance()
        return i1.hashCode() == i2.hashCode() && i1 == i2
    }

    private fun StateInfo.computeRemappingFunction(old: StateInfo): RemappingFunction? {
        if (maxTicket == NO_TICKET) return null
        val rf = IntArray(maxTicket + 1) { NO_TICKET }
        // Remap tickets of suspended operations according the order of suspension.
        for (i in suspendedOperations.indices) {
            rf[suspendedOperations[i].ticket] = old.suspendedOperations[i].ticket
        }
        // Remap tickets of resumed operations corresponding to equal resumption information.
        for (i in resumedOperations.indices) {
            rf[resumedOperations[i].resumedActorTicket] = old.resumedOperations[i].resumedActorTicket
        }
        return rf
    }


    private fun findFirstAvailableTicket(
        suspendedActorWithTickets: List<Operation>,
        resumedTicketsWithResults: MutableMap<Int, ResumedResult>
    ): Int {
        for (ticket in 0 until suspendedActorWithTickets.size + resumedTicketsWithResults.size) {
            if (suspendedActorWithTickets.find { it.ticket == ticket } == null &&
                !resumedTicketsWithResults.contains(ticket)) {
                return ticket
            }
        }
        return suspendedActorWithTickets.size + resumedTicketsWithResults.size
    }

    fun generateDotGraph(): String {
        val builder = StringBuilder()
        builder.appendln("digraph {")
        builder.appendln("\"${initialState.hashCode()}\" [style=filled, fillcolor=green]")
        builder.appendTransitions(initialState, IdentityHashMap())
        builder.appendln("}")
        return builder.toString()
    }

    private fun StringBuilder.appendTransitions(state: State, visitedStates: IdentityHashMap<State, Unit>) {
        state.transitionsByRequests.forEach { actor, transition ->
            appendln("${state.hashCode()} -> ${transition.nextState.hashCode()} [ label=\"<R,$actor:${transition.result},${transition.ticket}>, rf=${transition.rf?.contentToString()}\" ]")
            if (visitedStates.put(transition.nextState, Unit) === null) appendTransitions(transition.nextState, visitedStates)
        }
        state.transitionsByFollowUps.forEach { ticket, transition ->
            appendln("${state.hashCode()} -> ${transition.nextState.hashCode()} [ label=\"<F,$ticket:${transition.result},${transition.ticket}>, rf=${transition.rf?.contentToString()}\" ]")
            if (visitedStates.put(transition.nextState, Unit) === null) appendTransitions(transition.nextState, visitedStates)
        }
        state.transitionsByCancellations.forEach { ticket, transition ->
            appendln("${state.hashCode()} -> ${transition.nextState.hashCode()} [ label=\"<C,$ticket:${transition.result},${transition.ticket}>, rf=${transition.rf?.contentToString()}\" ]")
            if (visitedStates.put(transition.nextState, Unit) === null) appendTransitions(transition.nextState, visitedStates)
        }
    }
}

/**
 * Defines equivalency relation among LTS states (see [LTS.State]).
 * Stores information about the state: corresponding test [instance], execution status of operations that were used to create the given [state].
 *
 * The following state properties were chosen to define the state equivalency:
 *   1. The test instance corresponding to the given state with `hashCode` and `equals` defined by the user.
 *   2. The list of actor `requests` suspended on the LTS path to the [state]
 *   3. The set of pairs of resumed and the corresponding resuming actors.
 */
private class StateInfo(
    var state: State,
    val instance: Any,
    val suspendedOperations: List<Operation>,
    val resumedOperations: List<ResumptionInfo>
) {
    override fun equals(other: Any?): Boolean {
        if (other !is StateInfo) return false
        return instance == other.instance &&
                suspendedOperations.map { it.actor } == other.suspendedOperations.map { it.actor } &&
                resumedOperations == other.resumedOperations
    }

    override fun hashCode() = Objects.hash(
        instance,
        suspendedOperations.map { it.actor },
        resumedOperations
    )

    val maxTicket: Int
        get() = max(
            suspendedOperations.maxBy { it.ticket }?.ticket ?: NO_TICKET,
            resumedOperations.maxBy { it.resumedActorTicket }?.resumedActorTicket ?: NO_TICKET
        )
}

/**
 * When suspended operation is resumed by another thread,
 * [VerifierInterceptor.interceptContinuation] is called to intercept it's continuation.
 * Intercepted continuation just writes the result of the suspension point and reference to the unintercepted continuation
 * so that the calling thread could resume this continuation by itself.
 */
internal class VerifierInterceptor(
    private val ticket: Int,
    private val actor: Actor,
    private val resumedTicketsWithResults: MutableMap<Int, ResumedResult>
) : AbstractCoroutineContextElement(ContinuationInterceptor),
    ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return Continuation(StoreExceptionHandler() + Job()) { res ->
            // write the result only if the operation has not been cancelled
            if (!res.cancelledByLincheck()) {
                resumedTicketsWithResults[ticket] = ResumedResult(continuation as Continuation<Any?> to res)
                    .also { it.resumedActor = actor }
            }
        }
    }
}

/**
 * Passed as an argument to invoke suspendable operation
 *
 * [context] contains `ContinuationInterceptor` that intercepts continuation of the resumed operation
 * separating `request` and `follow-up` parts.
 *
 * If the operation has `follow-up` phase then [resumeWith] is executed at the end of the `follow-up`
 * writing the final result of the operation. Otherwise if there is no `follow-up`,
 * meaning that result of the suspension point equals the final result of the operation,
 * [resumeWith] will be executed just after resumption by the resuming thread.
 */
internal class Completion(
    private val ticket: Int,
    private val actor: Actor,
    private val resumedTicketsWithResults: MutableMap<Int, ResumedResult>
) : Continuation<Any?> {
    override val context: CoroutineContext
        get() = VerifierInterceptor(ticket, actor, resumedTicketsWithResults) + StoreExceptionHandler() + Job()

    override fun resumeWith(result: kotlin.Result<Any?>) {
        // write the result only if the operation has not been cancelled
        if (!result.cancelledByLincheck())
            resumedTicketsWithResults[ticket] = ResumedResult(null to result).also { it.resumedActor = actor }
    }
}

/**
 * The regular `Operation` is represented as a pair of [Actor] and [Result].
 * To support partial methods `Operation` should be extended.
 * A partial operation at first registers it's `request` and then waits for the fulfillment
 * of some precondition to become true to complete it's execution.
 * To be able to make transition by a blocking partial operation
 * it is divided into `request` and `followUp` parts as follows:
 * ```
 * [actor:result] = [actor_request : ticket] + [actor_followUp(ticket) : result]`
 * ```
 * If the required precondition is not yet set, the `request` part of an operation
 * suspends it's execution returning a [ticket] that will later be used to invoke completion
 * of the corresponding followUp.
 * Otherwise, the `request` part completes immediately returning the final result.
 *
 * Example of partial operations:
 * Assume, the rendezvous channel. There are two types of processes:
 * producers that perform `send` requests and consumers, which perform `receive` requests.
 * In order for a producer to `send` an element, it has to perform a rendezvous
 * handshake with a consumer, that will get this element. The consumer semantic is symmetrical.
 *
 * ```
 *  class Channel {
 *      fun send(e: Any)
 *      fun receive(): Any
 *   }
 * ```
 * To make sequential transitions by the blocking `send` and `receive` operations
 * they are divided as was described as above:
 * ```
 * 1. [receive_request():ticket] // `receive` registers it's request and suspends the execution
 * 2. [send_request(1):void]     // `send`'s `request` part completes without suspension as the receiver is already waiting
 * 3. [receive_request(ticket):res] // `receive` completes it's execution
 * ```
 */
data class Operation(
    val actor: Actor,
    val ticket: Int,
    val type: OperationType
)

enum class OperationType { REQUEST, FOLLOW_UP, CANCELLATION }

// should be less than all tickets
internal const val NO_TICKET = -1

private data class ResumptionInfo(
    val resumedActor: Actor,
    val by: Actor,
    val resumedActorTicket: Int
)

class TransitionInfo(
    /**
     * The next LTS state.
     */
    val nextState: State,
    /**
     * The set of tickets corresponding to resumed operation requests which follow-ups are available to be invoked.
     */
    val resumedTickets: ResumedTickets, // TODO inline class which contains either Int? or IntArray
    /**
     * The ticket assigned to the transition operation.
     *
     * If the executed operation was the `request` part that suspended it's execution,
     * the first available ticket is assigned.
     * Otherwise, ticket is [NO_TICKET].
     */
    val ticket: Int,
    /**
     * Ticket remapping function.
     * rf is not `null` if [nextState] was already present in LTS.
     * Stores correspondence between tickets assigned to operations on the current way to this state.
     * and previously assigned tickets.
     */
    val rf: RemappingFunction?, // TODO long + inline class
    /**
     * Transition result
     */
    val result: Result
) {
    /**
     * Returns `true` if the currently invoked operation is completed.
     */
    val operationCompleted: Boolean get() = result != NoResult && result != Suspended
}
