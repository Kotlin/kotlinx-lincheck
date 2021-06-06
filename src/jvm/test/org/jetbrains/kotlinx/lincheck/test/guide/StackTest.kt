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

import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Stack<T> {
    private val top  = AtomicReference<Node<T>?>(null)
    private val _size = AtomicInteger(0)

    fun push(value: T) {
        while (true) {
            val cur = top.get()
            val newTop = Node(cur, value)
            if (top.compareAndSet(cur, newTop)) { // try to add
                _size.incrementAndGet() // <-- INCREMENT SIZE
                return
            }
        }
    }

    fun pop(): T? {
        while (true) {
            val cur = top.get() ?: return null // is stack empty?
            if (top.compareAndSet(cur, cur.next)) { // try to retrieve
                _size.decrementAndGet() // <-- DECREMENT SIZE
                return cur.value
            }
        }
    }

    override fun toString() =
        buildString {
            append("[")
            var node = top.get()
            while (node != null) {
                append(node.value)
                node = node.next
                if (node != null) append(",")
            }
            append("]")
    }

    val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)

@Param(name = "value", gen = IntGen::class, conf = "1:10") // values are ints in 1..10 range
class StackTest : VerifierState() {
    private val s = Stack<Int>()

    @Operation fun push(@Param(name = "value") value: Int) = s.push(value)
    @Operation fun pop() = s.pop()
    @Operation fun size() = s.size

    @StateRepresentation
    fun stackreperesentation() = s.toString()

    override fun extractState(): Any = s.toString()

    @Test
    fun runStressTest() = StressOptions()
        .logLevel(LoggingLevel.INFO)
        .check(this::class)

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .actorsBefore(5)
        .actorsAfter(5)
        .threads(2).actorsPerThread(2)
        .check(this::class.java)
}