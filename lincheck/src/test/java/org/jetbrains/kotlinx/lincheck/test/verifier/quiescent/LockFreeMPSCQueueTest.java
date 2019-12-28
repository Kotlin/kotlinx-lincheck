package org.jetbrains.kotlinx.lincheck.test.verifier.quiescent;

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

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.QuiescentConsistencyVerifier;
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.QuiescentConsistent;
import org.junit.Test;

@StressCTest(verifier = QuiescentConsistencyVerifier.class, requireStateEquivalenceImplCheck = false)
@OpGroupConfig(name = "consumer", nonParallel = true)
public class LockFreeMPSCQueueTest {
    private LockFreeMPSCQueue<Integer> q = new LockFreeMPSCQueue<>();

    @Operation(group = "consumer")
    @QuiescentConsistent
    public Integer removeFirstOrNull() {
        return q.removeFirstOrNull();
    }

    @Operation
    public boolean addLast(@Param(gen = IntGen.class, conf = "distinct") Integer val) {
        return q.addLast(val);
    }

    @Operation(runOnce = true)
    @QuiescentConsistent
    public void close() {
        q.close();
    }

    @Test
    public void test() {
        LinChecker.check(LockFreeMPSCQueueTest.class);
    }
}