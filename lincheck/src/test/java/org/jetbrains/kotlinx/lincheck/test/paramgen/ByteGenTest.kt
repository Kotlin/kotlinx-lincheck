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

import org.jetbrains.kotlinx.lincheck.paramgen.ByteGen
import org.junit.Test

class ByteGenTest {
    @Test(expected = IllegalArgumentException::class)
    fun testIncorrectBounds() {
        ByteGen("300:400")
    }

    @Test
    fun testUnique() {
        ByteGen("distinct").generate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testTooManyUniques() {
        val gen = ByteGen("distinct")
        for (i in Byte.MIN_VALUE..Byte.MAX_VALUE + 1) // more than byte capacity
            gen.generate()
    }
}
