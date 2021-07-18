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
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.*

abstract class TestNodeExecution {
    var actorId = 0
    var runner: Runner? = null
    var testInstance: Any? = null
    lateinit var objArgs: Array<Any>
    lateinit var results: Array<Result?>

    abstract suspend fun runOperation(i: Int): Any?

    fun crash() {
        if (actorId - 1 >= 0 && actorId <= results.size && results[actorId - 1] == null) {
            results[actorId - 1] = CrashResult
        }
    }

    fun crashRemained() {
        for (i in results.indices) {
            if (results[i] == null) {
                results[i] = CrashResult
            }
        }
        actorId = results.size + 1
    }

    fun setSuspended(actors: List<Actor>) {
        val lastOp = actorId - 1
        if (lastOp >= 0 && lastOp < results.size && results[lastOp] == null && actors[lastOp].isSuspendable) {
            results[actorId - 1] = if (actors[lastOp].method.returnType == Void.TYPE) {
                SuspendedVoidResult
            } else {
                Suspended
            }
        }
        for (i in results.indices) {
            if (results[i] == null) {
                results[i] = NoResult
            }
        }
    }
}
