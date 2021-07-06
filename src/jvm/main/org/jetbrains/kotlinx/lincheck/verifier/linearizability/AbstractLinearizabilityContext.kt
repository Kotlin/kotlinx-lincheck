/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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
package org.jetbrains.kotlinx.lincheck.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

abstract class AbstractLinearizabilityContext : VerifierContext {
    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State)
        : super(scenario, results, state)

    constructor(
        scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State,
        executed: IntArray, suspended: BooleanArray, tickets: IntArray
    ) : super(scenario, results, state, executed, suspended, tickets)

    abstract fun createContainer(): Container
    abstract fun processResult(container: Container, threadId: Int)
    abstract fun createContext(
        scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State,
        executed: IntArray, suspended: BooleanArray, tickets: IntArray
    ): VerifierContext

    override fun nextContext(threadId: Int): ContextContainer {
        if (isCompleted(threadId)) return ContextContainer.EMPTY
        // Check whether an actorWithToken from the specified thread can be executed
        // in accordance with the rule that all actors from init part should be
        // executed at first, after that all actors from parallel part, and
        // all actors from post part should be executed at last.
        val legal = when (threadId) {
            0 -> true // INIT: we already checked that there is an unprocessed actorWithToken
            in 1..scenario.threads -> isCompleted(0) && hblegal(threadId) // PARALLEL
            else -> initCompleted && parallelCompleted // POST
        }
        if (!legal) return ContextContainer.EMPTY
        val actorId = executed[threadId]
        val actor = scenario[threadId][actorId]
        val expectedResult = results[threadId][actorId]
        // Check whether the operation has been suspended and should be followed by cancellation
        val ticket = tickets[threadId]
        val promptCancel = actor.promptCancellation && ticket != NO_TICKET && expectedResult === Cancelled
        if (suspended[threadId] || promptCancel) {
            return if (actor.cancelOnSuspension && expectedResult === Cancelled)
                createContainer().also { container ->
                    container.addContext(state.nextByCancellation(actor, ticket).createContext(threadId))
                }
            else ContextContainer.EMPTY
        }
        val container = createContainer()
        processResult(container, threadId)

        // Try to make a transition by the next actor from the current thread,
        // passing the ticket corresponding to the current thread.
        val nextContext = state.next(actor, expectedResult, tickets[threadId])?.createContext(threadId)
        if (nextContext !== null) {
            container.addContext(nextContext)
        }
        return container
    }

    // checks whether the transition does not violate the happens-before relation constructed on the clocks
    private fun hblegal(threadId: Int): Boolean {
        val actorId = executed[threadId]
        val clocks = results.parallelResultsWithClock[threadId - 1][actorId].clockOnStart
        for (i in 1..scenario.threads) {
            if (executed[i] < clocks[i - 1]) return false
        }
        return true
    }

    private fun TransitionInfo.createContext(threadId: Int): VerifierContext {
        val nextExecuted = executed.copyOf()
        val nextSuspended = suspended.copyOf()
        val nextTickets = tickets.copyOf()
        // update tickets
        nextTickets[threadId] = if (result == Suspended) ticket else NO_TICKET
        if (rf != null) { // remapping
            nextTickets.forEachIndexed { tid, ticket ->
                if (tid != threadId && ticket != NO_TICKET)
                    nextTickets[tid] = rf[ticket]
            }
        }
        // update "suspended" statuses
        nextSuspended[threadId] = result == Suspended
        for (tid in threads) {
            if (nextTickets[tid] in resumedTickets) // note, that we have to use remapped tickets here!
                nextSuspended[tid] = false
        }
        // mark this step as "executed" if the operation was not suspended or is cancelled
        if (operationCompleted) nextExecuted[threadId]++
        // create new context
        return createContext(
            scenario = scenario,
            state = nextState,
            results = results,
            executed = nextExecuted,
            suspended = nextSuspended,
            tickets = nextTickets
        )
    }

    interface Container : ContextContainer {
        fun addContext(context: VerifierContext)
    }
}
