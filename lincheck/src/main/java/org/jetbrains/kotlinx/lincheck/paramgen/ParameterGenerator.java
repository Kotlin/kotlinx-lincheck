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

import org.jetbrains.kotlinx.lincheck.annotations.Operation;

/**
 * The implementation of this interface is used to generate parameters
 * for {@link Operation operation}.
  */
public interface ParameterGenerator<T> {
    String DISTINCT_MODIFIER = "distinct";

    T generate();
    default void reset() {}

    final class Dummy implements ParameterGenerator<Object> {
        @Override
        public Object generate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reset() { throw new UnsupportedOperationException(); }
    }
}
