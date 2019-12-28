package org.jetbrains.kotlinx.lincheck.paramgen;

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

public class ByteGen implements ParameterGenerator<Byte> {
    private final IntGen intGen;

    public ByteGen(String configuration) {
        intGen = new IntGen(configuration);
        intGen.checkRange((int) Byte.MIN_VALUE, (int) Byte.MAX_VALUE, "byte");
    }

    public Byte generate() {
        return (byte) (int) intGen.generate();
    }

    @Override
    public void reset() {
        intGen.reset();
    }
}
