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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

/**
 * Configuration for [random search][ModelCheckingStrategy] strategy.
 */
abstract class ManagedCTestConfiguration(testClass: Class<*>, iterations: Int, threads: Int, actorsPerThread: Int, actorsBefore: Int,
                                         actorsAfter: Int, generatorClass: Class<out ExecutionGenerator>, verifierClass: Class<out Verifier>,
                                         val checkObstructionFreedom: Boolean, val hangingDetectionThreshold: Int, val invocationsPerIteration: Int,
                                         val guarantees: List<ManagedStrategyGuarantee>, requireStateEquivalenceCheck: Boolean, minimizeFailedScenario: Boolean,
                                         sequentialSpecification: Class<*>?, timeoutMs: Long, val eliminateLocalObjects: Boolean
) : CTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, generatorClass,
    verifierClass, requireStateEquivalenceCheck, minimizeFailedScenario, sequentialSpecification, timeoutMs) {
    companion object {
        const val DEFAULT_INVOCATIONS = 10000
        const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false
        const val DEFAULT_ELIMINATE_LOCAL_OBJECTS = true
        const val DEFAULT_HANGING_DETECTION_THRESHOLD = 100
        const val LIVELOCK_EVENTS_THRESHOLD = 5000
        val DEFAULT_GUARANTEES = listOf( // These classes use WeakHashMap, and thus, their code is non-deterministic.
            // Non-determinism should not be present in managed executions, but luckily the classes
            // can be just ignored, so that no thread context switches are added inside their methods.
            forClasses(
                "kotlinx.coroutines.internal.StackTraceRecoveryKt"
            ).allMethods().ignore(),  // Some atomic primitives are common and can be analyzed from a higher level of abstraction.
            // For this purpose they are treated as if they are atomic instructions.
            forClasses { className: String -> isTrustedPrimitive(className) }
                .allMethods()
                .treatAsAtomic()
        )
    }
}