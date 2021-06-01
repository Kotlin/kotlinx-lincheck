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
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class MultiMap {
    val map = ConcurrentHashMap<Int, List<Int>>()

    fun addBroken(key: Int, value: Int) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: Int) = map.get(key)
}

@Param(name = "key", gen = IntGen::class, conf = "1:1")
class MultiMapFailingTest : VerifierState() {
    private val map = MultiMap()

    @Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.addBroken(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    override fun extractState() = map.map

    @Test
    fun runStressTest() = StressOptions()
        .requireStateEquivalenceImplCheck(false)
        .check(this::class.java)

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        //.requireStateEquivalenceImplCheck(false)
        .minimizeFailedScenario(false)
        .actorsBefore(0).actorsAfter(0)
        .threads(2).actorsPerThread(2)
        .check(this::class.java)

    @Test
    fun runModularTesting() = ModelCheckingOptions()
        .requireStateEquivalenceImplCheck(false)
        .minimizeFailedScenario(false)
        .addGuarantee(forClasses("org.jetbrains.kotlinx.lincheck.tran\$f*rmed.java.util.concurrent.ConcurrentHashMap").allMethods().treatAsAtomic())
        .check(this::class.java)
}