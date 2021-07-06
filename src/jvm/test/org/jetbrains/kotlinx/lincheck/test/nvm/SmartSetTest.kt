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

package org.jetbrains.kotlinx.lincheck.test.nvm

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.nvm.SmartSet
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

@StressCTest(
    threads = 1,
    sequentialSpecification = UsualSet::class,
    actorsPerThread = 100,
    invocationsPerIteration = 1,
    iterations = 1000
)
class SmartSetTest {
    private val set = SmartSet<Int>()

    @Operation
    fun add(value: Int) = set.add(value)
    @Operation
    fun remove(value: Int) = set.remove(value)
    @Operation
    fun size() = set.size

    @Operation
    fun snapshot(): String {
        val s = sortedSetOf<Int>()
        set.forEach { s.add(it) }
        return s.joinToString()
    }

    @Test
    fun test() = LinChecker.check(this::class.java)
}

class UsualSet : VerifierState() {
    private val set = sortedSetOf<Int>()
    fun add(value: Int) = set.add(value)
    fun remove(value: Int) = set.remove(value)
    fun size() = set.size
    fun snapshot() = set.joinToString()
    override fun extractState() = set
}
