package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability;

/*
* #%L
* Lincheck
* %%
* Copyright (C) 2015 - 2018 Devexperts, LLC
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

import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.paramgen.*;
import org.jetbrains.kotlinx.lincheck.strategy.stress.*;
import org.jetbrains.kotlinx.lincheck.verifier.*;
import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;

@Param(name = "key", gen = IntGen.class, conf = "1:5")
@Param(name = "value", gen = IntGen.class, conf = "distinct")
@StressCTest(actorsPerThread = 10, threads = 2, invocationsPerIteration = 1000, iterations = 100, actorsBefore = 10, actorsAfter = 10)
public class ConcurrentHashMapTest extends VerifierState {
    private ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();

    @Operation
    public Integer put(@Param(name = "key") Integer key, @Param(name = "value") Integer value) {
        return map.put(key, value);
    }

    @Operation
    public Integer get(@Param(name = "key") Integer key) {
        return map.get(key);
    }

    @Test
    public void test() { LinChecker.check(ConcurrentHashMapTest.class); }

    @Override
    protected Object extractState() {
        return map;
    }
}
