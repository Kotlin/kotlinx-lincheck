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

package org.jetbrains.kotlinx.lincheck.verifier.linearizability.durable

import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.get
import org.jetbrains.kotlinx.lincheck.verifier.AbstractLTSVerifier
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.AbstractLinearizabilityContext

class DurableLinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS = LTS(sequentialSpecification = sequentialSpecification)

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext =
        DurableLinearizabilityContext(scenario, results, lts.initialState)
}

private class DurableLinearizabilityContext : AbstractLinearizabilityContext {
    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State)
        : super(scenario, results, state)

    constructor(
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray
    ) : super(scenario, results, state, executed, suspended, tickets)

    override fun createContainer(): AbstractLinearizabilityContext.Container = Container()
    override fun processResult(container: AbstractLinearizabilityContext.Container, threadId: Int) {
        val actorId = executed[threadId]
        val expectedResult = results[threadId][actorId]
        if (expectedResult == CrashResult) {
            container.addContext(skipOperation(threadId))
        }
    }

    override fun createContext(
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray
    ) = DurableLinearizabilityContext(scenario, results, state, executed, suspended, tickets)

    private fun skipOperation(threadId: Int): DurableLinearizabilityContext {
        val newExecuted = executed.copyOf()
        newExecuted[threadId]++
        return DurableLinearizabilityContext(
            scenario = scenario,
            state = state,
            results = results,
            executed = newExecuted,
            suspended = suspended,
            tickets = tickets
        )
    }

    private class Container : AbstractLinearizabilityContext.Container {
        private var context1: VerifierContext? = null
        private var context2: VerifierContext? = null

        override fun addContext(context: VerifierContext) {
            if (context1 == null) {
                context1 = context
            } else if (context2 == null) {
                context2 = context
            } else {
                error("Container size exceeded")
            }
        }

        override fun iterator() = object : Iterator<VerifierContext> {
            override fun hasNext() = context1 !== null || context2 !== null
            override fun next(): VerifierContext {
                val tmp1 = context1
                if (tmp1 != null) {
                    context1 = null
                    return tmp1
                }
                val tmp2 = context2
                if (tmp2 != null) {
                    context2 = null
                    return tmp2
                }
                error("Container size exceeded")
            }
        }
    }
}