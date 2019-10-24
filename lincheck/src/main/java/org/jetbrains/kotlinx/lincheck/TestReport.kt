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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionOutcome

class TestReport(val errorType: ErrorType) : ExecutionOutcome {
    val success get() = errorType == ErrorType.NO_ERROR
    var errorIteration: Int? = null
    var errorInvocation: Int? = null
    var errorDetails: String? = null
}

enum class ErrorType {
    NO_ERROR,
    LIVELOCK,
    DEADLOCK,
    OBSTRUCTION_FREEDOM_VIOLATED,
    INCORRECT_RESULTS
}