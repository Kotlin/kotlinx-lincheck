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
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.ClassVisitor
import java.lang.reflect.*

class StressStrategy(
    testCfg: StressCTestConfiguration,
    private val testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    private val verifier: Verifier,
    private val recoverabilityModel: RecoverabilityModel
) : Strategy(scenario) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner: Runner = ParallelThreadsRunner(
        this, testClass, validationFunctions, stateRepresentationFunction, testCfg.timeoutMs,
        UseClocks.RANDOM, recoverabilityModel
    )

    init {
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun run(): LincheckFailure? {
        runner.use {
            // Run invocations
            for (invocation in 0 until invocations) {
                when (val ir = runner.run()) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, ir.results))
                            return IncorrectResultsFailure(scenario, ir.results)
                    }
                    else -> return ir.toLincheckFailure(scenario)
                }
            }
            return null
        }
    }

    override fun needsTransformation() = recoverabilityModel.needsTransformation()
    override fun createTransformer(cv: ClassVisitor) =
        recoverabilityModel.createTransformerWrapper(recoverabilityModel.createTransformer(cv, testClass), testClass)
}