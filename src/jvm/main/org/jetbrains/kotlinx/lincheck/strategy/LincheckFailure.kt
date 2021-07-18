/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

sealed class LincheckFailure(
    val scenario: ExecutionScenario,
    val trace: Trace?
) {
    override fun toString() = StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    val results: ExecutionResult,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class DeadlockWithDumpFailure(
    scenario: ExecutionScenario,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    val exception: Throwable,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    val functionName: String,
    val exception: Throwable,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    val reason: String,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class LivelockWithDumpFailure(
    scenario: ExecutionScenario,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal fun InvocationResult.toLincheckFailure(scenario: ExecutionScenario, trace: Trace? = null) = when (this) {
    is DeadlockInvocationResult -> DeadlockWithDumpFailure(scenario, threadDump, trace)
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailure(scenario, exception, trace)
    is ValidationFailureInvocationResult -> ValidationFailure(scenario, functionName, exception, trace)
    is ObstructionFreedomViolationInvocationResult -> ObstructionFreedomViolationFailure(scenario, reason, trace)
    is LivelockInvocationResult -> LivelockWithDumpFailure(scenario, threadDump, trace)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}