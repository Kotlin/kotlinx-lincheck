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

package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

private const val THREADS_NUMBER = 3
private const val N = 3

internal interface MCAS {
    operator fun get(index: Int): Int
    fun compareAndSet(old: List<Int>, new: List<Int>): Boolean
}

internal class ListGen(config: String) : ParameterGenerator<List<Int>> {
    private val used = hashSetOf(List(N) { 0 })
    override fun generate() =
        if (Random.nextBoolean()) List(N) { Random.nextInt(2) }.also { used.add(it) }
        else used.random()
}

@Param(name = "list", gen = ListGen::class)
@StressCTest(
    sequentialSpecification = SequentialMCAS::class,
    threads = THREADS_NUMBER,
    minimizeFailedScenario = false
)
internal class MCASTest {
    private val cas = ConcurrentMCAS()

    @Operation
    fun get(@Param(gen = IntGen::class, conf = "0:${N - 1}") index: Int) = cas[index]

    @Operation
    fun compareAndSet(@Param(name = "list") old: List<Int>, @Param(name = "list") new: List<Int>) =
        cas.compareAndSet(old, new)

    @Test
    fun test() = LinChecker.check(this::class.java)
}

internal class SequentialMCAS : MCAS, VerifierState() {
    private val data = MutableList(N) { 0 }
    override fun extractState() = data
    override fun get(index: Int) = data[index]
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        if (!data.indices.all { i -> data[i] == old[i] }) return false
        data.indices.forEach { i -> data[i] = new[i] }
        return true
    }
}

internal data class WordDescriptor(val old: Int, val new: Int, val parent: MCASDescriptor)
internal enum class Status { ACTIVE, SUCCESSFUL, FAILED }
internal class MCASDescriptor(s: Status) {
    val status = AtomicReference(s)
    var words = listOf<WordDescriptor>()
}

internal class ConcurrentMCAS : MCAS {
    private val data: List<AtomicReference<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { AtomicReference(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.get() }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].get()
            val p = wd.parent

            if (p !== self && p.status.get() == Status.ACTIVE) {
                MCAS(p)
                continue
            }
            return if (p.status.get() == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === self.words[index]) continue@loop
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.get() != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL else Status.FAILED)
        return self.status.get() == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }
}
