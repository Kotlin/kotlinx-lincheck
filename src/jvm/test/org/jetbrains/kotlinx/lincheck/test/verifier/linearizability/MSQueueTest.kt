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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class MSQueueTest : AbstractLincheckTest() {
    private val q = MSQueue<Int>()

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop() = q.pop()

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(SequentialQueue::class.java)
    }
}

class SequentialQueue : VerifierState() {
    private val q = ArrayDeque<Int>()

    fun push(value: Int) {
        q.offer(value)
    }

    fun pop() = q.poll()
    fun pop(ignore: Int) = pop()

    override fun extractState() = q.toList()
}

class MSQueue<T> : VerifierState() {
    private class Node<S>(val next: AtomicReference<Node<S>> = AtomicReference<Node<S>>(null), val value: S)

    private val head = AtomicReference(Node<T?>(value = null))
    private val tail = AtomicReference(head.get())

    override fun extractState(): Any {
        val result = mutableListOf<T>()
        var node = head.get()
        while (node.next.get() != null) {
            result.add(node.next.get().value!!)
            node = node.next.get()
        }
        return result
    }

    fun push(value: T) {
        val newNode = Node<T?>(value = value)
        while (true) {
            val tailNode = tail.get()
            if (tailNode.next.compareAndSet(null, newNode)) {
                tail.compareAndSet(tailNode, newNode)
                return
            } else {
                tail.compareAndSet(tailNode, tailNode.next.get())
            }
        }
    }

    fun pop(): T? {
        while (true) {
            val h = head.get()
            val next = h.next.get() ?: return null
            tail.compareAndSet(h, next)
            if (head.compareAndSet(h, next)) {
                return next.value!!
            }
        }
    }
}
