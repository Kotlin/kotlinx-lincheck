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

package org.jetbrains.kotlinx.lincheck.test.distributed.totalorder

import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.distributed.Node

abstract class OrderCheckNode<M>(val env: Environment<M, M>) : Node<M> {
    @Validate
    fun validate() {
       val logs = env.getLogs().toList()
       for (l in logs) {
           for (i in l.indices) {
               for (j in i + 1 until l.size) {
                   check(logs.none {
                       val first = it.lastIndexOf(l[i])
                       val second = it.lastIndexOf(l[j])
                       first != -1 && second != -1 && first >= second
                   }) {
                       "logs=$logs, first=${l[i]}, second=${l[j]}"
                   }
               }
           }
       }
       val sent = env.events().flatMap {
           it.filterIsInstance<MessageSentEvent<Message>>().map { it.message }.filterIsInstance<RequestMessage>()
       }
       sent.forEach { m ->
           check(logs.filterIndexed { index, _ -> index != m.from }.all { it.contains(m) }) {
               m.toString()
           }
       }
    }
}