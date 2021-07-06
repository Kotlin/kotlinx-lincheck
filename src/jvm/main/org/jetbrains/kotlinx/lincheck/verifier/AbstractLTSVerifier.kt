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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*

/**
 * An abstraction for verifiers which use the labeled transition system (LTS) under the hood.
 * The main idea of such verifiers is finding a path in LTS, which starts from the initial
 * LTS state (see [LTS.initialState]) and goes through all actors with the specified results.
 * To determine which transitions are possible from the current state, we store related
 * to the current path prefix information in the special [VerifierContext], which determines
 * the next possible transitions using [VerifierContext.nextContext] function. This verifier
 * uses depth-first search to find a proper path.
 */
abstract class AbstractLTSVerifier(protected val sequentialSpecification: Class<*>) : CachedVerifier() {
    abstract val lts: LTS
    abstract fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext

    override fun verifyResultsImpl(scenario: ExecutionScenario, results: ExecutionResult) = createInitialContext(scenario, results).verify()

    private fun VerifierContext.verify(): Boolean {
        // Check if a possible path is found.
        if (completed) return true
        // Traverse through next possible transitions using depth-first search (DFS). Note that
        // initial and post parts are represented as threads with ids `0` and `threads + 1` respectively.
        for (threadId in threads) {
            for (nextContext in nextContext(threadId)) {
                if (nextContext.verify()) return true
            }
        }
        return false
    }

    override fun checkStateEquivalenceImplementation() = lts.checkStateEquivalenceImplementation()
}

/**
 *  Reflects the current path prefix information and stores the current LTS state
 *  (which essentially indicates the data structure state) for a single step of a legal path search
 *  in LTS-based verifiers. It counts next possible transitions via [nextContext] function.
 */
abstract class VerifierContext(
    /**
     * Current execution scenario.
     */
    protected val scenario: ExecutionScenario,
    /**
     * Expected execution results
     */
    protected val results: ExecutionResult,
    /**
     * LTS state of this context
     */
    val state: LTS.State,
    /**
     * Number of executed actors in each thread. Note that initial and post parts
     * are represented as threads with ids `0` and `threads + 1` respectively.
     */
    protected val executed: IntArray = IntArray(scenario.threads + 2),
    /**
     * For every scenario thread stores whether it is suspended or not.
     */
    protected val suspended: BooleanArray = BooleanArray(scenario.threads + 2),
    /**
     * For every thread it stores a ticket assigned to the last executed actor by [LTS].
     * A ticket is assigned from the range (0 .. threads) to an actor that suspends it's execution,
     * after being resumed the actor is invoked with this ticket to complete it's execution.
     * If an actor does not suspend, the assigned ticket equals [NO_TICKET].
     */
    protected val tickets: IntArray = IntArray(scenario.threads + 2) { NO_TICKET }
) {
    /**
     * Counts next possible states and the corresponding contexts if the specified thread is executed.
     */
    abstract fun nextContext(threadId: Int): ContextContainer

    /**
     * Returns `true` if all actors in the specified thread are executed.
     */
    fun isCompleted(threadId: Int) = executed[threadId] == scenario[threadId].size

    /**
     * Returns `true` if the initial part is completed.
     */
    val initCompleted: Boolean get() = isCompleted(0)

    /**
     * Returns `true` if all actors from the parallel scenario part are executed.
     */
    val parallelCompleted: Boolean get() = completedThreads(1..scenario.threads) == scenario.threads

    /**
     * Returns `true` if all threads completed their execution.
     */
    val completed: Boolean get() = completedThreads + suspendedThreads == scenario.threads + 2

    /**
     * Range for all threads
     */
    val threads: IntRange get() = 0..(scenario.threads + 1)

    /**
     * The number of threads that expectedly suspended their execution.
     */
    private val suspendedThreads: Int get() = threads.count { t -> suspended[t] && results[t][executed[t]] === Suspended }

    /**
     * Returns the number of completed threads from the specified range.
     */
    private fun completedThreads(range: IntRange) = range.count { t -> isCompleted(t) }

    /**
     * Returns the number of completed scenario threads.
     */
    private val completedThreads: Int get() = completedThreads(threads)
}

interface ContextContainer : Iterable<VerifierContext> {
    companion object {
        val EMPTY = object : ContextContainer {
            override fun iterator() = object : Iterator<VerifierContext> {
                override fun hasNext() = false
                override fun next(): VerifierContext {
                    error("Container size exceeded")
                }

            }

        }
    }
}
