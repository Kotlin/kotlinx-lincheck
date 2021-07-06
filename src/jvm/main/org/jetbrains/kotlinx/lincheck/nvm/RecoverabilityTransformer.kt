/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.lang.Integer.max


class RecoverabilityTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    private lateinit var name: String

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        this.name = name!!
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name == "<init>") return mv
        return RecoverableMethodTransformer(mv, access, name, descriptor, this.name)
    }
}

private val CRASH_NAME = Type.getInternalName(CrashError::class.java)

private open class RecoverableBaseMethodTransformer(
    mv: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?,
    className: String
) : GeneratorAdapter(ASM_API, mv, access, name, descriptor) {
    protected var shouldTransform = false
    protected var beforeName = ""
    protected var recoverName = ""
    protected val tryLabel = Label()
    protected val catchLabel = Label()

    private val completedVariable: Int by lazy { newLocal(Type.BOOLEAN_TYPE) }
    private val classType = Type.getType("L$className;")

    /** Check whether method has [Recoverable] annotation. */
    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        val av = super.visitAnnotation(descriptor, visible)
        if (descriptor != Type.getDescriptor(Recoverable::class.java)) return av
        shouldTransform = true
        return object : AnnotationVisitor(ASM_API, av) {
            override fun visit(name: String?, value: Any?) {
                super.visit(name, value)
                if (name == "recoverMethod") {
                    recoverName = value as String
                } else if (name == "beforeMethod") {
                    beforeName = value as String
                }
            }
        }
    }

    /**
     * Call [name] method with signature [descriptor] until it completes successfully.
     * @return index of local variable where result is stored or -1 in case of void return type
     */
    protected fun callUntilSuccess(name: String, descriptor: String?): Int {
        val (tryLabel, catchLabel, endLabel) = List(3) { Label() }
        visitTryCatchBlock(tryLabel, catchLabel, catchLabel, CRASH_NAME)

        val returnType = Type.getReturnType(descriptor)
        val result = if (returnType == Type.VOID_TYPE) -1 else newLocal(returnType).also {
            // init result
            pushDefaultValue(returnType)
            storeLocal(it)
        }

        push(false)
        storeLocal(completedVariable)

        visitLabel(tryLabel)

        // invoke `name` method
        loadThis()
        loadArgs()
        invokeVirtual(classType, Method(name, descriptor))
        if (returnType != Type.VOID_TYPE) {
            storeLocal(result)
        }
        push(true)
        storeLocal(completedVariable)
        goTo(endLabel)

        // ignore exception, try again
        visitLabel(catchLabel)
        pop()

        visitLabel(endLabel)
        // while not successful
        loadLocal(completedVariable)
        ifZCmp(EQ, tryLabel)
        return result
    }

    /** Push a default value of type [type] on stack. */
    private fun pushDefaultValue(type: Type) {
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.INT -> push(0)
            Type.LONG -> push(0L)
            Type.DOUBLE -> push(0.0)
            Type.FLOAT -> push(0.0f)
            Type.ARRAY, Type.METHOD, Type.OBJECT -> visitInsn(Opcodes.ACONST_NULL)
        }
    }
}


/**
 * Modifies body of method if it is marked with [Recoverable] annotation.
 * Generates a call to before method(if present), wraps method body with try-catch
 * and calls recover method in case of a crash.
 */
private class RecoverableMethodTransformer(
    mv: MethodVisitor,
    access: Int,
    name: String?,
    private val descriptor: String?,
    className: String
) : RecoverableBaseMethodTransformer(mv, access, name, descriptor, className) {

    /** Call before if name is non-empty and wrap method body with try-catch. */
    override fun visitCode() {
        super.visitCode()
        if (!shouldTransform) return
        visitTryCatchBlock(tryLabel, catchLabel, catchLabel, CRASH_NAME)

        if (beforeName.isNotEmpty()) {
            val beforeDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, *Type.getType(descriptor).argumentTypes)
            callUntilSuccess(beforeName, beforeDescriptor)
        }

        visitLabel(tryLabel)
    }

    /** Call recover method in case of crash. */
    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        if (shouldTransform) {
            visitLabel(catchLabel)
            pop()
            val result = callUntilSuccess(recoverName.ifEmpty { name }, descriptor)
            if (result != -1) {
                loadLocal(result)
            }
            returnValue()
            super.visitMaxs(max(1 + maxStack, 1 + argumentTypes.size), maxLocals)
        } else {
            super.visitMaxs(maxStack, maxLocals)
        }
    }
}
