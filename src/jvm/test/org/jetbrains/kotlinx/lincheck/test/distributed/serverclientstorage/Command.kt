/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import java.util.HashMap

sealed class Command(val id : Int)

class GetCommand(val key : Int, id : Int) : Command(id) {
    override fun toString() = "GetCommand(key=$key, id=$id)"
}
class PutCommand(val key : Int, val value : Int, id : Int) : Command(id) {
    override fun toString() = "PutCommand(key=$key, value=$value, id=$id)"
}
class AddCommand(val key : Int, val value : Int, id : Int) : Command(id) {
    override fun toString() = "AddCommand(key=$key, value=$value, id=$id)"
}
class ContainsCommand(val key : Int, id : Int) : Command(id) {
    override fun toString() = "ContainsCommand(key=$key, id=$id)"
}
class RemoveCommand(val key : Int, id : Int) : Command(id) {
    override fun toString() = "RemoveCommand(key=$key, id=$id)"
}
class GetResult(val res : Int?, id : Int) : Command(id) {
    override fun toString() = "GetResult(res=$res, id=$id)"
}
class PutResult(val res : Int?, id : Int) : Command(id){
    override fun toString() = "PutResult(res=$res, id=$id)"
}
class AddResult(val res : Int?, id : Int) : Command(id){
    override fun toString() = "AddResult(res=$res, id=$id)"
}
class ContainsResult(val res : Boolean, id : Int) : Command(id) {
    override fun toString() = "ContainsResult(res=$res, id=$id)"
}
class RemoveResult(val res : Int?, id : Int) : Command(id) {
    override fun toString() = "RemoveResult(res=$res, id=$id)"
}
class ErrorResult(val error : Throwable, id : Int) : Command(id) {
    override fun toString() = "ErrorResult(error=$error, id=$id)"
}


class SingleNode {
    private val storage = HashMap<Int, Int>()

    @Operation
    suspend fun contains(key: Int) = storage.contains(key)

    @Operation
    suspend fun put(key: Int, value: Int) = storage.put(key, value)

    @Operation
    suspend fun get(key: Int) = storage[key]

    @Operation
    suspend fun remove(key: Int) = storage.remove(key)

    suspend fun add(key: Int, value: Int) = storage.put(key, storage.getOrDefault(key, 0) + value)
}