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
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.distributed.CrashError

/**
 * This class represents a result corresponding to
 * the specified [scenario][ExecutionScenario] execution.
 *
 * All the result parts should have the same dimensions as the scenario.
 */
data class ExecutionResult(
    /**
     * Results of the initial sequential part of the execution.
     * @see ExecutionScenario.initExecution
     */
    val initResults: List<Result>,
    /**
     * State representation at the end of the init part.
     */
    val afterInitStateRepresentation: String?,
    /**
     * Results of the parallel part of the execution with the clock values at the beginning of each one.
     * @see ExecutionScenario.parallelExecution
     */
    val parallelResultsWithClock: List<List<ResultWithClock>>,
    /**
     * State representation at the end of the parallel part.
     */
    val afterParallelStateRepresentation: String?,
    /**
     * Results of the last sequential part of the execution.
     * @see ExecutionScenario.postExecution
     */
    val postResults: List<Result>,
    /**
     * State representation at the end of the scenario.
     */
    val afterPostStateRepresentation: String?,
    /**
     * Crashes occurred while execution.
     */
    internal val crashes: List<List<CrashError>> = emptyList()
) {
    constructor(initResults: List<Result>, parallelResultsWithClock: List<List<ResultWithClock>>, postResults: List<Result>) :
            this(initResults, null, parallelResultsWithClock, null, postResults, null)
}

val ExecutionResult.withEmptyClocks: ExecutionResult get() = ExecutionResult(
    this.initResults,
    this.afterInitStateRepresentation,
    this.parallelResultsWithClock.map { it.withEmptyClock() },
    this.afterParallelStateRepresentation,
    this.postResults,
    this.afterPostStateRepresentation
)

val ExecutionResult.withoutCrashes: ExecutionResult get() = if (crashes.isEmpty()) this else copy(crashes = emptyList())

val ExecutionResult.parallelResults: List<List<Result>> get() = parallelResultsWithClock.map { it.map { r -> r.result } }

// for tests
fun ExecutionResult.equalsIgnoringClocks(other: ExecutionResult) = this.withEmptyClocks == other.withEmptyClocks
