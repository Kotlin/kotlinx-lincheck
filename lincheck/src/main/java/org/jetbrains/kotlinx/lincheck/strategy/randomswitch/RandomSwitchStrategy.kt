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
package org.jetbrains.kotlinx.lincheck.strategy.randomswitch

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategyBase
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * RandomSwitchStrategy just switches at any codeLocation to a random thread
 */
class RandomSwitchStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        verifier: Verifier,
        testCfg: RandomSwitchCTestConfiguration,
        reporter: Reporter
) : ManagedStrategyBase(testClass, scenario, verifier, reporter, testCfg.maxRepetitions, testCfg.checkObstructionFreedom) {
   // maximum number of thread switches that managed strategy may use to search for incorrect execution
    private val maxInvocations = testCfg.invocationsPerIteration
    private var switchProbability = startSwitchProbability

    companion object {
        private const val startSwitchProbability = 0.05
        private const val endSwitchProbability = 1.0
    }

    @Throws(Exception::class)
    override fun run() {
        // should only used once for each Strategy object

        try {
            repeat(maxInvocations){
                // switch probability changes linearly from startSwitchProbability to endSwitchProbability
                switchProbability = startSwitchProbability + it * (endSwitchProbability - startSwitchProbability) / maxInvocations
                checkResults(runInvocation())
            }
        } finally {
            runner.close()
        }
    }

    override fun shouldSwitch(threadId: Int): Boolean {
        // TODO: can reduce number of random calls using geometric distribution
        return executionRandom.nextDouble() < switchProbability
    }

    override fun initializeInvocation() {
        executionRandom.endLastPoint() // a point corresponds to an invocation
        super.initializeInvocation()
    }
}