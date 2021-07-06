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
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.nvm.StrategyRecoveryOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

/**
 * Options for [model checking][ModelCheckingStrategy] strategy.
 */
class ModelCheckingOptions : ManagedOptions<ModelCheckingOptions, ModelCheckingCTestConfiguration>() {
    override fun createTestConfigurations(testClass: Class<*>): ModelCheckingCTestConfiguration {
        return ModelCheckingCTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter,
                executionGenerator, verifier, checkObstructionFreedom, hangingDetectionThreshold, invocationsPerIteration,
                guarantees, requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
                chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, eliminateLocalObjects,
                verboseTrace, customScenarios, recover.createModel(StrategyRecoveryOptions.MANAGED))
    }
}