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
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}

class CounterTest : VerifierState() {
    private val c = Counter()

    @Operation fun inc() = c.inc()

    @Operation fun get() = c.get()

    override fun extractState(): Any = c.get()

    @StateRepresentation
    fun counterReperesentation() = c.get().toString()

    @Test
    fun runStressTest() = StressOptions()
        .actorsBefore(2) // Init part
        .threads(2).actorsPerThread(2)
        .actorsAfter(1) // Post part
        .minimizeFailedScenario(true)
        .check(this::class)

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions().check(this::class)
}

