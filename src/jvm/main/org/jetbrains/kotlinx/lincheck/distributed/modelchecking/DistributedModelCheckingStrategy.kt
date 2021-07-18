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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.stress.newResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.toLincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method


class DistributedModelCheckingStrategy<Message, Log>(
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    private val verifier: Verifier
) : Strategy(scenario) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner: DistributedModelCheckingRunner<Message, Log> =
        DistributedModelCheckingRunner(this, testCfg, testClass, validationFunctions, stateRepresentationFunction)

    init {
        // Create runner
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun run(): LincheckFailure? {
        println(scenario)
        runner.use { runner ->
            // Run invocations
            var invocation = 0
            while (invocation < invocations) {
                //println("INVOCATION $invocation")
                val ir = runner.run()
                when (ir) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, ir.results)) {
                            val stateRepresentation = runner.constructStateRepresentation()
                            return IncorrectResultsFailure(
                                scenario,
                                ir.results.newResult(stateRepresentation)
                            ).also {
                                runner.storeEventsToFile(it)
                                //println("Found error")
                               // debugLogs.forEach {println(it)}
                            }
                        }
                    }
                    else -> {
                        return ir.toLincheckFailure(scenario).also {
                            runner.storeEventsToFile(it)
                            println("Found error")
                            //debugLogs.forEach {println(it)}
                        }
                    }
                }
                if (runner.isFullyExplored()) {
                    println("Finish on $invocation")
                    return null
                }
                if (!runner.isInterrupted) invocation++
            }
            return null
        }
    }
}
