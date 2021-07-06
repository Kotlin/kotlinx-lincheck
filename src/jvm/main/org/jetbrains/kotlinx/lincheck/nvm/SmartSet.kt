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

package org.jetbrains.kotlinx.lincheck.nvm

const val SIZE = 4

/**
 * Set optimised for small number of elements.
 */
class SmartSet<E> {
    var size = 0
        private set
    private var data: Any? = null

    fun clear() {
        size = 0
        data = null
    }

    fun add(element: E): Boolean {
        when {
            size == 0 -> {
                size = 1
                data = element
                return true
            }
            size == 1 -> {
                if (element == data) {
                    return false
                }
                val tmp = asValue()
                val newData = arrayOfNulls<Any?>(SIZE)
                newData[0] = tmp
                newData[1] = element
                data = newData
                size = 2
                return true
            }
            size <= SIZE -> {
                val list = asList()
                val i = list.indexOf(element)
                if (i in 0 until size) return false
                if (size < SIZE) {
                    list[size] = element
                } else {
                    val newSet = linkedSetOf(element)
                    newSet.addAll(list.map { it as E })
                    data = newSet
                }
                size++
                return true
            }
            else -> {
                val result = asSet().add(element)
                if (result) size++
                return result
            }
        }
    }

    fun remove(element: E): Boolean {
        when {
            size == 0 -> return false
            size == 1 -> {
                if (element == data) {
                    data = null
                    size = 0
                    return true
                }
                return false
            }
            size <= SIZE -> {
                val list = asList()
                val i = list.indexOf(element)
                if (i == -1 || i >= size) return false
                size--
                list[i] = list[size]
                if (size == 1) {
                    data = list[0]
                }
                return true
            }
            else -> {
                val set = asSet()
                val result = set.remove(element)
                if (result) size--
                if (size <= SIZE) {
                    val newList = arrayOfNulls<Any?>(SIZE)
                    set.forEachIndexed { index, elem ->
                        newList[index] = elem
                    }
                    data = newList
                }
                return result
            }
        }
    }

    inline fun forEach(action: (E) -> Unit) {
        when {
            size == 0 -> return
            size == 1 -> {
                action(asValue())
                return
            }
            size <= SIZE -> {
                val a = asList()
                for (i in 0 until size) {
                    action(a[i] as E)
                }
            }
            else -> {
                asSet().forEach(action)
            }
        }
    }

    fun asValue() = data as E
    fun asList() = data as Array<Any?>
    fun asSet() = data as MutableSet<E>
}
