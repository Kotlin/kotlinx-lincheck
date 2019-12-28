/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.junit.Test
import org.junit.Assert.*

class IntGenTest {
    @Test
    fun testWithoutParams() {
        IntGen("").generate()
    }

    @Test
    fun testWithBounds() {
        val gen = IntGen("1:5")
        for (i in 0 until 10) {
            val value = gen.generate()
            assertTrue(value in 1..5)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnknownParameters() {
        IntGen("1:3:5")
    }

    @Test
    fun testOneUniqueGenerate() {
        IntGen("distinct").generate()
    }

    @Test
    fun testUniqueness() {
        val gen = IntGen("distinct")
        val set = mutableSetOf<Int>()
        val iterations = 100
        for (i in 0 until iterations) {
            val value = gen.generate()
            assertFalse(set.contains(value))
            set.add(value)
        }
    }
}
