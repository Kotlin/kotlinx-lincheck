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

import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class MSQueueBlocking {
    private val DUMMY = Node(0)
    private val head = AtomicReference(DUMMY)
    private val tail = AtomicReference(DUMMY)

    public fun enqueue(x: Int) {
        val newTail = Node(x)
        while (true) { // CAS loop
            val curTail = tail.get()
            if (curTail.next.compareAndSet(null, newTail)) {
                // node has been added -> move tail forward
                tail.compareAndSet(curTail, newTail)
                break
            } else {
                // help other enqueue operations to move tail forward
                //tail.compareAndSet(curTail, curTail.next.get())
            }
        }
    }

    public fun dequeue(): Int? {
        while (true) { // CAS loop
            val curHead = head.get()
            val headNext = curHead.next.get() ?: return null
            if (head.compareAndSet(curHead, headNext)) {
                return headNext.value
            }
        }
    }

    class Node(val value: Int, val next: AtomicReference<Node?> = AtomicReference(null))
}

class ObstructionFreedomViolationTest  {
    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    @Test
    fun runStressTest() = StressOptions()
        .requireStateEquivalenceImplCheck(false)
        .check(this::class.java)

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class.java)
}