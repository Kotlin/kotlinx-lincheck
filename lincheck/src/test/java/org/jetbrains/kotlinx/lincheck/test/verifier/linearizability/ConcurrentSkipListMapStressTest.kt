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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.ConcurrentSkipListMap

@Param(name = "value", gen = IntGen::class, conf = "distinct")
@StressCTest(verifier = LinearizabilityVerifier::class, iterations = 10, invocationsPerIteration = 1000, actorsBefore = 10, actorsAfter = 10, actorsPerThread = 5, threads = 3)
class ConcurrentSkipListMapStressTest : VerifierState() {

    val skiplistMap = ConcurrentSkipListMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = skiplistMap.put(key, value)

    @Operation
    fun get(key: Int) = skiplistMap.get(key)

    @Operation
    fun containsKey(key: Int) = skiplistMap.containsKey(key)

    @Operation
    fun remove(key: Int) = skiplistMap.remove(key)

    @Operation
    fun ceilingEntry(key: Int) = skiplistMap.ceilingEntry(key)

    @Operation
    fun ceilingKey(key: Int) = skiplistMap.ceilingKey(key)

    @Test
    fun test() = LinChecker.check(ConcurrentSkipListMapStressTest::class.java)

    override fun extractState() = skiplistMap.toMap()
}
