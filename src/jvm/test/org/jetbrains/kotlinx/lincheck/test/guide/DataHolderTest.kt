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

class DataHolder {
    var data: String = "aaa"
    @Volatile var version = 0

    fun write(new: String) { // single thread updater
        version++ // lock the holder for reads
        data = new
        version++ // release the holder for reads
    }

    fun read(): String {
        while(true) {
            val curVersion = version
            // Is there a concurrent update?
            if (curVersion % 2 == 1) continue
            // Read the data
            val data = this.data
            // Return if version is the same
            if (curVersion == version) return data
        }
    }

    fun writeBlocking(new: String) = synchronized(this) {
        data = new
    }

    fun readBlocking(): String = synchronized(this) {
        return data
    }
}

@OpGroupConfig(name = "writer", nonParallel = true)
class DataHolderTest {
    private val dataHolder = DataHolder()

    @Operation(group = "writer")
    fun write(s: String) = dataHolder.write(s)

    @Operation
    fun read() = dataHolder.read()

    @Test
    fun runStressTest() = StressOptions()
        .requireStateEquivalenceImplCheck(false)
        .check(this::class.java)

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .requireStateEquivalenceImplCheck(false)
        .checkObstructionFreedom(true)
        .check(this::class.java)
}