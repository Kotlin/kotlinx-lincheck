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

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.LongAdder

class LongAdderTest : VerifierState() {
    private val la = LongAdder()

    @Operation
    fun increment() = la.increment()

    @Operation
    fun decrement() = la.decrement()

    @Operation
    fun sum() = la.sum()

    override fun extractState(): Any = la

    @Test
    fun runStressTest() = StressOptions()
        .threads(3)
        .iterations(100000)
        .check(this::class.java)

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .threads(3)
        .check(this::class.java)

//    = Invalid execution results =
//    Parallel part:
//    | decrement(): void         | sum(): 2 | increment(): void         |
//    | sum():       0    [1,-,1] |          | increment(): void [-,-,1] |

}