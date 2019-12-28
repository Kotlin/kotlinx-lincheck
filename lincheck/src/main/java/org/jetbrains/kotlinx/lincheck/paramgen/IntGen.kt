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
package org.jetbrains.kotlinx.lincheck.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator.DISTINCT_MODIFIER
import kotlin.random.Random

private const val DEFAULT_BEGIN = -10
private const val DEFAULT_END = 10

class IntGen(configuration: String) : ParameterGenerator<Int> {
    private val genImpl: IntParameterGenerator

    init {
        val args = tokenizeConfiguration(configuration)
        genImpl = when {
            args.isEmpty() -> RangeIntGen(DEFAULT_BEGIN..DEFAULT_END)
            args.size == 1 && args[0] == DISTINCT_MODIFIER -> DistinctIntGen()
            args.size == 2 -> RangeIntGen(args[0].toInt()..args[1].toInt())
            else -> throw IllegalArgumentException("There should be zero arguments or '$DISTINCT_MODIFIER' " +
                    "or two arguments (begin and end) separated with colon")
        }
    }

    override fun generate(): Int = genImpl.generate()

    override fun reset() = genImpl.reset()

    fun checkRange(min: Int, max: Int, type: String) = genImpl.checkRange(min, max, type)

    private interface IntParameterGenerator : ParameterGenerator<Int> {
        fun checkRange(min: Int, max: Int, type: String)
    }

    private class RangeIntGen(val range: IntRange) : IntParameterGenerator {
        private val random = Random(0)

        override fun generate(): Int = range.random(random)

        override fun checkRange(min: Int, max: Int, type: String) {
            require(min in range && max in range) {
                "Illegal range for $type type: [${range.start}; ${range.endInclusive})"
            }
        }
    }

    private class DistinctIntGen : IntParameterGenerator {
        private var nextToGenerate = 0
        private var maxValue = Int.MAX_VALUE
        private var type = ""

        override fun generate(): Int {
            val result = nextToGenerate++
            require (result <= maxValue) { "Too many unique values are queried for type $type" }
            return result
        }

        override fun reset() {
            nextToGenerate = 0
        }

        override fun checkRange(min: Int, max: Int, type: String) {
            maxValue = max
            this.type = type
        }
    }
}

internal fun tokenizeConfiguration(configuration: String) = configuration.replace("\\s".toRegex(), "").split("[:\\s]".toRegex()).filter(String::isNotEmpty)