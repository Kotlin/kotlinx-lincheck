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

import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.*
import java.util.*

/**
 * Common options for all managed strategies.
 */
abstract class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> : Options<OPT, CTEST>() {
    protected var invocationsPerIteration = DEFAULT_INVOCATIONS
    protected var checkObstructionFreedom = DEFAULT_CHECK_OBSTRUCTION_FREEDOM
    protected var hangingDetectionThreshold = DEFAULT_HANGING_DETECTION_THRESHOLD
    protected val guarantees: MutableList<ManagedStrategyGuarantee> = ArrayList(DEFAULT_GUARANTEES)
    protected var eliminateLocalObjects: Boolean = DEFAULT_ELIMINATE_LOCAL_OBJECTS;

    /**
     * Use the specified number of scenario invocations to study possible interleavings in each iteration.
     * Lincheck can use less invocation if it requires less one to study all possible interleavings.
     */
    fun invocationsPerIteration(invocations: Int): OPT = applyAndCast {
        invocationsPerIteration = invocations
    }

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    fun checkObstructionFreedom(checkObstructionFreedom: Boolean = true): OPT = applyAndCast {
        this.checkObstructionFreedom = checkObstructionFreedom
    }

    /**
     * Use the specified maximum number of repetitions to detect endless loops (hangs).
     * A found loop will force managed execution to switch the executing thread or report
     * ab obstruction-freedom violation if [checkObstructionFreedom] is set.
     */
    fun hangingDetectionThreshold(hangingDetectionThreshold: Int): OPT = applyAndCast {
        this.hangingDetectionThreshold = hangingDetectionThreshold
    }

    /**
     * Add a guarantee that methods in some classes are either correct in terms of concurrent execution or irrelevant.
     * These guarantees can be used for optimization. For example, we can add a guarantee that all the methods
     * in `java.util.concurrent.ConcurrentHashMap` are correct and this way the strategy will not try to switch threads
     * inside these methods. We can also mark methods in logging classes irrelevant so that they will be completely
     * ignored while studying all possible interleavings.
     */
    fun addGuarantee(guarantee: ManagedStrategyGuarantee): OPT = applyAndCast {
        guarantees.add(guarantee)
    }

    /**
     * Internal, DO NOT USE.
     */
    internal fun eliminateLocalObjects(eliminateLocalObjects: Boolean) {
        this.eliminateLocalObjects = eliminateLocalObjects;
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> ManagedOptions<OPT, CTEST>.applyAndCast(
                block: ManagedOptions<OPT, CTEST>.() -> Unit
        ) = this.apply {
            block()
        } as OPT
    }
}