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

package org.jetbrains.kotlinx.lincheck.test.distributed.common

import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.NodeAddressResolver
import org.junit.Test

class NodeAddressResolverTest {
    class AClass : Node<Int> {
        override fun onMessage(message: Int, sender: Int) {
            TODO("Not yet implemented")
        }
    }

    class BClass : Node<Int> {
        override fun onMessage(message: Int, sender: Int) {
            TODO("Not yet implemented")
        }
    }

    class CClass : Node<Int> {
        override fun onMessage(message: Int, sender: Int) {
            TODO("Not yet implemented")
        }
    }

    @Test
    fun test() {
        val addressResolver = NodeAddressResolver(AClass::class.java, 3, mapOf(BClass::class.java to (4 to false), CClass::class.java to (5 to true)), emptyMap())
        repeat(3) {
            check(addressResolver[it] == AClass::class.java)
        }
        repeat(4) {
            check(addressResolver[it + 3] == BClass::class.java)
        }
        repeat(5) {
            check(addressResolver[it + 7] == CClass::class.java)
        }
        check(addressResolver[AClass::class.java] == listOf(0, 1, 2))
        check(addressResolver[BClass::class.java] == listOf(3, 4, 5, 6))
        check(addressResolver[CClass::class.java] == listOf(7, 8, 9, 10, 11))
    }
}