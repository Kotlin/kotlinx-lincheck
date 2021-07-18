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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

data class VectorClock(val clock: IntArray) {
    val nodes: Int get() = clock.size
    val empty: Boolean get() = clock.all { it == 0 }
    operator fun get(i: Int) = clock[i]

    override fun toString() = clock.joinToString(prefix = "[", separator = ",", postfix = "]") {
        if (it == 0) "-" else "$it"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorClock
        return clock.contentEquals(other.clock)
    }

    fun happensBefore(other: VectorClock) : Boolean {
        for (i in clock.indices) {
            if (other[i] < this[i]) {
                return false
            }
        }
        return true
    }

    override fun hashCode() = clock.contentHashCode()
}

