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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

/**
 * Configuration for [stress][StressStrategy] strategy.
 */
class StressCTestConfiguration(testClass: Class<*>, iterations: Int, threads: Int, actorsPerThread: Int, actorsBefore: Int, actorsAfter: Int,
                               generatorClass: Class<out ExecutionGenerator>, verifierClass: Class<out Verifier>,
                               val invocationsPerIteration: Int, requireStateEquivalenceCheck: Boolean, minimizeFailedScenario: Boolean,
                               sequentialSpecification: Class<*>, timeoutMs: Long, customScenarios: List<ExecutionScenario>,
                               recoverabilityModel: RecoverabilityModel
) : CTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, generatorClass, verifierClass,
    requireStateEquivalenceCheck, minimizeFailedScenario, sequentialSpecification, timeoutMs, customScenarios, recoverabilityModel) {
    override fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario, validationFunctions: List<Method>,
                                stateRepresentationMethod: Method?, verifier: Verifier) =
        StressStrategy(this, testClass, scenario, validationFunctions, stateRepresentationMethod, verifier, recoverabilityModel)

    companion object {
        const val DEFAULT_INVOCATIONS = 10000
    }
}
