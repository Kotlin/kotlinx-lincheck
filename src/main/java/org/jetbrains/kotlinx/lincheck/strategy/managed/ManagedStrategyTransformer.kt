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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader
import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.UnsafeHolder
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*
import java.util.stream.Collectors
import kotlin.reflect.jvm.javaMethod


/**
 * This transformer inserts [ManagedStrategy] methods invocations.
 */
internal class ManagedStrategyTransformer(
        cv: ClassVisitor?,
        val codeLocations: MutableList<CodeLocation>,
        private val guarantees: List<ManagedGuarantee>,
        private val shouldMakeStateRepresentation: Boolean
) : ClassVisitor(ASM_API, ClassRemapper(cv, JavaUtilRemapper())) {
    private lateinit var className: String
    private var classVersion = 0
    private var fileName: String? = null

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String, interfaces: Array<String>) {
        className = name
        classVersion = version
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String, debug: String?) {
        fileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(access: Int, mname: String, desc: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
        var access = access
        // replace native method VMSupportsCS8 in AtomicLong with stub
        if (access and ACC_NATIVE != 0 && mname == "VMSupportsCS8") {
            val mv = super.visitMethod(access and ACC_NATIVE.inv(), mname, desc, signature, exceptions)
            return VMSupportsCS8MethodGenerator(GeneratorAdapter(mv, access and ACC_NATIVE.inv(), mname, desc))
        }
        val isSynchronized = access and ACC_SYNCHRONIZED != 0
        if (isSynchronized) {
            access = access xor ACC_SYNCHRONIZED // disable synchronized
        }
        var mv = super.visitMethod(access, mname, desc, signature, exceptions)
        mv = JSRInlinerAdapter(mv, access, mname, desc, signature, exceptions)
        mv = SynchronizedBlockTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        if (isSynchronized) {
            // synchronized method is replaced with synchronized lock
            mv = SynchronizedBlockAddingTransformer(mname, GeneratorAdapter(mv, access, mname, desc), className, access, classVersion)
        }
        mv = ClassInitializationTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ManagedGuaranteeTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = CallStackTraceLoggingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = HashCodeStubTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = UnsafeTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = WaitNotifyTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ParkUnparkTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = LocalObjectManagingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = SharedVariableAccessMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = TimeStubTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = RandomTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = TryCatchBlockSorter(mv, access, mname, desc, signature, exceptions)
        return mv
    }

    /**
     * Changes package of transformed classes from java/util package, excluding some
     */
    private class JavaUtilRemapper : Remapper() {
        override fun map(name: String): String {
            val normalizedName = name.replace("/", ".")
            val isException = Throwable::class.java.isAssignableFrom(Class.forName(normalizedName))
            val isTrustedAtomicPrimitive = isTrustedPrimitive(normalizedName)
            // function package is not transformed, because AFU uses it and thus there will be transformation problems
            if (name.startsWith("java/util/") && !isTrustedAtomicPrimitive && !isException)
                return TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME + name
            return name
        }
    }

    /**
     * Generates body of a native method VMSupportsCS8.
     * Native methods in java.util can not be transformed properly, so should be replaced with stubs
     */
    private class VMSupportsCS8MethodGenerator(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, null) {
        override fun visitEnd() = adapter.run {
                visitCode()
                push(true) // suppose that we always have CAS for Long
                returnValue()
                visitMaxs(1, 0)
                visitEnd()
            }
    }

    /**
     * Adds invocations of ManagedStrategy methods before reads and writes of shared variables
     */
    private inner class SharedVariableAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) = adapter.run {
            if (isFinalField(owner, name)) {
                super.visitFieldInsn(opcode, owner, name, desc)
                return
            }

            when (opcode) {
                GETSTATIC -> {
                    invokeBeforeSharedVariableRead(name)
                    super.visitFieldInsn(opcode, owner, name, desc)
                    afterSharedVariableRead(Type.getType(desc))
                }
                GETFIELD -> {
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = newLabel()
                    dup()
                    invokeOnLocalObjectCheck()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    invokeBeforeSharedVariableRead(name)
                    visitLabel(skipCodeLocationBefore)

                    super.visitFieldInsn(opcode, owner, name, desc)

                    val skipCodeLocationAfter = newLabel()
                    loadLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationAfter)
                    // initialize ReadCodeLocation only if is not a local object
                    afterSharedVariableRead(Type.getType(desc))
                    visitLabel(skipCodeLocationAfter)
                }
                PUTSTATIC -> {
                    beforeSharedVariableWrite(name, Type.getType(desc))
                    super.visitFieldInsn(opcode, owner, name, desc)
                    invokeMakeStateRepresentation()
                }
                PUTFIELD -> {
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = newLabel()
                    dupOwnerOnPutField(desc)
                    invokeOnLocalObjectCheck()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    beforeSharedVariableWrite(name, Type.getType(desc))
                    visitLabel(skipCodeLocationBefore)

                    super.visitFieldInsn(opcode, owner, name, desc)

                    val skipCodeLocationAfter = newLabel()
                    loadLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationAfter)
                    // make state representation only if is not a local object
                    invokeMakeStateRepresentation()
                    visitLabel(skipCodeLocationAfter)
                }
                else -> throw IllegalArgumentException("Unknown field opcode")
            }
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                    val skipCodeLocation = adapter.newLabel()
                    dup2() // arr, ind
                    pop() // arr, ind -> arr
                    invokeOnLocalObjectCheck()
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocation)
                    // add strategy invocation only if is not a local object
                    invokeBeforeSharedVariableRead()
                    visitLabel(skipCodeLocation)
                    super.visitInsn(opcode)
                }
                AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = adapter.newLabel()
                    dupArrayOnArrayStore(opcode)
                    invokeOnLocalObjectCheck()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    beforeSharedVariableWrite(null, getArrayStoreType(opcode))
                    visitLabel(skipCodeLocationBefore)

                    super.visitInsn(opcode)

                    val skipCodeLocationAfter = newLabel()
                    loadLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationAfter)
                    // initialize make state representation only if is not a local object
                    invokeMakeStateRepresentation()
                    visitLabel(skipCodeLocationAfter)
                }
                else -> super.visitInsn(opcode)
            }
        }

        // STACK: array, index, value -> array, index, value, arr
        private fun dupArrayOnArrayStore(opcode: Int) = adapter.run {
            val type = getArrayStoreType(opcode)
            val value = newLocal(type)
            storeLocal(value)
            dup2() // array, index, array, index
            pop() // array, index, array
            val array: Int = adapter.newLocal(OBJECT_TYPE)
            storeLocal(array) // array, index
            loadLocal(value) // array, index, value
            loadLocal(array) // array, index, value, array
        }

        // STACK: object, value -> object, value, object
        private fun dupOwnerOnPutField(desc: String) = adapter.run {
            if ("J" != desc && "D" != desc) {
                dup2() // object, value, object, value
                pop() // object, value, object
            } else {
                // double or long. Value takes 2 machine words.
                dup2X1() // value, object, value
                pop2() // value, object
                dupX2() // object, value, object
            }
        }

        // STACK: value that was read
        private fun afterSharedVariableRead(valueType: Type) = adapter.run {
            val readValue = newLocal(valueType)
            copyLocal(readValue)
            // initialize ReadCodeLocation
            val codeLocation = codeLocations.lastIndex /// the last created code location
            loadStrategy()
            push(codeLocation)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODELOCATION_DESCRIPTION_METHOD)
            checkCast(READ_CODELOCATION_TYPE)
            loadLocal(readValue)
            box(valueType)
            invokeVirtual(READ_CODELOCATION_TYPE, ADD_READ_VALUE_METHOD)
        }

        // STACK: value to be written
        private fun beforeSharedVariableWrite(fieldName: String? = null, valueType: Type) {
            invokeBeforeSharedVariableWrite(fieldName)
            captureWrittenValue(valueType)
        }

        // STACK: value to be written
        private fun captureWrittenValue(valueType: Type) = adapter.run {
            val storedValue = newLocal(valueType)
            copyLocal(storedValue) // save store value
            // initialize WriteCodeLocation with stored value
            val codeLocation = codeLocations.lastIndex // the last created code location
            loadStrategy()
            push(codeLocation)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODELOCATION_DESCRIPTION_METHOD)
            checkCast(WRITE_CODELOCATION_TYPE)
            loadLocal(storedValue)
            box(valueType)
            invokeVirtual(WRITE_CODELOCATION_TYPE, ADD_WRITTEN_VALUE_METHOD)
        }

        private fun getArrayStoreType(opcode: Int): Type = when (opcode) {
            AASTORE -> OBJECT_TYPE
            IASTORE -> Type.INT_TYPE
            FASTORE -> Type.FLOAT_TYPE
            BASTORE -> Type.BOOLEAN_TYPE
            CASTORE -> Type.CHAR_TYPE
            SASTORE -> Type.SHORT_TYPE
            LASTORE -> Type.LONG_TYPE
            DASTORE -> Type.DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }

        private fun invokeBeforeSharedVariableRead(fieldName: String? = null) = invokeBeforeSharedVariableReadOrWrite(BEFORE_SHARED_VARIABLE_READ_METHOD) { ste -> ReadCodeLocation(fieldName, ste)}

        private fun invokeBeforeSharedVariableWrite(fieldName: String? = null) = invokeBeforeSharedVariableReadOrWrite(BEFORE_SHARED_VARIABLE_WRITE_METHOD) { ste -> WriteCodeLocation(fieldName, ste)}

        private fun invokeBeforeSharedVariableReadOrWrite(method: Method, codeLocationConstructor: (StackTraceElement) -> CodeLocation) {
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(codeLocationConstructor)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }

        // STACK: object
        private fun invokeOnLocalObjectCheck() {
            val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(objectLocal)
            loadLocalObjectManager()
            adapter.loadLocal(objectLocal)
            adapter.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, IS_LOCAL_OBJECT_METHOD)
        }
    }

    /**
     * Add strategy method invocations corresponding to ManagedGuarantee guarantees.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer for optimization purposes.
     */
    private inner class ManagedGuaranteeTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            when (classifyGuaranteeType(owner, name)) {
                ManagedGuaranteeType.IGNORE -> {
                    invokeBeforeIgnoredSectionEntering()
                    adapter.visitMethodInsn(opcode, owner, name, desc, itf)
                    invokeAfterIgnoredSectionLeaving()
                }
                ManagedGuaranteeType.TREAT_AS_ATOMIC -> {
                    invokeBeforeAtomicMethodCall()
                    invokeBeforeIgnoredSectionEntering()
                    adapter.visitMethodInsn(opcode, owner, name, desc, itf)
                    invokeAfterIgnoredSectionLeaving()
                    invokeMakeStateRepresentation()
                }
                null -> adapter.visitMethodInsn(opcode, owner, name, desc, itf)
            }

        }

        /**
         * Find a guarantee that a method has if any
         */
        private fun classifyGuaranteeType(className: String, methodName: String): ManagedGuaranteeType? {
            for (guarantee in guarantees)
                if (guarantee.methodPredicate(methodName) && guarantee.classPredicate(className.replace("/", ".")))
                    return guarantee.type
            return null
        }

        private fun invokeBeforeAtomicMethodCall() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.push(codeLocations.lastIndex) // reuse code location
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_ATOMIC_METHOD_CALL_METHOD)
        }
    }

    /**
     * Makes all <clinit> sections ignored, because managed execution in <clinit> can lead to a deadlock.
     * SharedVariableAccessMethodTransformer should be earlier than this transformer not to create switch points before
     * beforeIgnoredSectionEntering invocations.
     */
    private inner class ClassInitializationTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter)  {
        private val isClinit = methodName == "<clinit>"

        override fun visitCode() {
            if (isClinit)
                invokeBeforeIgnoredSectionEntering()
            mv.visitCode()
        }

        override fun visitInsn(opcode: Int) {
            if (isClinit) {
                when (opcode) {
                    ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> invokeAfterIgnoredSectionLeaving()
                    else -> { }
                }
            }
            mv.visitInsn(opcode)
        }
    }

    /**
     * Adds strategy method invocations before and after method calls.
     */
    private inner class CallStackTraceLoggingTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            val isStrategyCall = isStrategyCall(owner)
            if (isStrategyCall) {
                adapter.visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            beforeMethodCall(name, Type.getArgumentTypes(desc))
            val codeLocation = codeLocations.lastIndex // the code location that was created at the previous line
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
            invokeAfterMethodCall()
            afterMethodCall(Method(name, desc).returnType, codeLocation)
        }

        private fun isStrategyCall(owner: String) = owner.startsWith("org/jetbrains/kotlinx/lincheck/strategy")

        // STACK: param_1 param_2 ... param_n
        private fun beforeMethodCall(methodName: String, paramTypes: Array<Type>) {
            invokeBeforeMethodCall(methodName)
            captureParameters(paramTypes)
        }

        // STACK: returned value (unless void)
        private fun afterMethodCall(returnType: Type, codeLocation: Int) = adapter.run {
            if (returnType == Type.VOID_TYPE) return // no return value
            val returnedValue = newLocal(returnType)
            copyLocal(returnedValue)
            // initialialize MethodCallCodeLocation return value
            loadStrategy()
            push(codeLocation)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODELOCATION_DESCRIPTION_METHOD)
            checkCast(METHOD_CALL_CODELOCATION_TYPE)
            loadLocal(returnedValue)
            box(returnType)
            invokeVirtual(METHOD_CALL_CODELOCATION_TYPE, ADD_RETURNED_VALUE_METHOD)
        }

        // STACK: param_1 param_2 ... param_n
        private fun captureParameters(paramTypes: Array<Type>) = adapter.run {
            if (paramTypes.isEmpty()) return // nothing to capture
            val params = copyParameters(paramTypes)
            // create array of parameters
            push(paramTypes.size)
            visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
            val array = newLocal(OBJECT_ARRAY_TYPE)
            storeLocal(array)
            for (i in paramTypes.indices) {
                loadLocal(array)
                push(i)
                loadLocal(params[i])
                box(paramTypes[i]) // in case it is a primitive type
                arrayStore(OBJECT_TYPE)
            }
            // initialize MethodCallCodeLocation parameter values
            loadStrategy()
            val codeLocation = codeLocations.lastIndex /// the last created code location
            push(codeLocation)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODELOCATION_DESCRIPTION_METHOD)
            checkCast(METHOD_CALL_CODELOCATION_TYPE)
            loadLocal(array)
            invokeVirtual(METHOD_CALL_CODELOCATION_TYPE, ADD_PARAMETERS_METHOD)
        }

        private fun invokeBeforeMethodCall(methodName: String) {
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation { ste -> MethodCallCodeLocation(methodName, ste) }
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_METHOD_CALL_METHOD)
        }

        private fun invokeAfterMethodCall() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_METHOD_CALL_METHOD)
        }
    }

    /**
     * Replaces `Unsafe.getUnsafe` with `UnsafeHolder.getUnsafe`, because
     * transformed java.util classes can not access Unsafe directly after transformation.
     */
    private class UnsafeTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (owner == "sun/misc/Unsafe" && name == "getUnsafe") {
                // load Unsafe
                adapter.invokeStatic(UNSAFE_HOLDER_TYPE, GET_UNSAFE_METHOD)
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Replaces Object.hashCode and Any.hashCode invocations with just zero.
     * This transformer prevents non-determinism due to the native hashCode implementation,
     * which typically returns memory address of the object. There is no guarantee that
     * memory addresses will be the same in different runs.
     */
    private class HashCodeStubTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            val isAnyHashCodeInvocation = owner == "kotlin/Any" && name == "hashCode"
            val isObjectHashCodeInvocation = owner == "java/lang/Object" && name == "hashCode"
            if (isAnyHashCodeInvocation || isObjectHashCodeInvocation) {
                // instead of calling object.hashCode just return zero
                adapter.pop() // remove object from the stack
                adapter.push(0)
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Replaces `System.nanoTime` and `System.currentTimeMillis` with stubs to prevent non-determinism
     */
    private class TimeStubTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")) {
                adapter.push(1337L) // any constant value
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Makes java.util.Random and all classes that extend it deterministic.
     * In every Random method invocation replaces the owner with Random from ManagedStateHolder.
     */
    private class RandomTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        private val randomMethods = Random::class.java.declaredMethods

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (opcode == INVOKEVIRTUAL && extendsRandom(owner.replace("/", ".")) && isRandomMethod(name, desc)) {
                val locals = adapter.storeParameters(desc)
                adapter.pop() // pop replaced Random
                loadRandom()
                adapter.loadLocals(locals)
                adapter.visitMethodInsn(opcode, "java/util/Random", name, desc, itf)
                return
            }
            // there is also a static method in ThreadLocalRandom that is used inside java.util.concurrent.
            // it is replaced with nextInt method.
            val isThreadLocalRandomMethod = owner == "java/util/concurrent/ThreadLocalRandom"
            if (isThreadLocalRandomMethod && name == "nextSecondarySeed") {
                loadRandom()
                adapter.visitMethodInsn(INVOKEVIRTUAL, "java/util/Random", "nextInt", desc, itf)
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }

        private fun loadRandom() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStateHolder::random.name, RANDOM_TYPE)
        }

        private fun extendsRandom(className: String) = java.util.Random::class.java.isAssignableFrom(Class.forName(className))

        private fun isRandomMethod(methodName: String, desc: String): Boolean = randomMethods.any {
                val method = Method.getMethod(it)
                method.name == methodName && method.descriptor == desc
            }
    }

    /**
     * Adds invocations of ManagedStrategy methods before monitorenter and monitorexit instructions
     */
    private inner class SynchronizedBlockTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                MONITORENTER -> {
                    val opEnd = newLabel()
                    val skipMonitorEnter: Label = newLabel()
                    dup()
                    invokeBeforeLockAcquire()
                    // check whether the lock should be really acquired
                    ifZCmp(GeneratorAdapter.EQ, skipMonitorEnter)
                    monitorEnter()
                    goTo(opEnd)
                    visitLabel(skipMonitorEnter)
                    pop()
                    visitLabel(opEnd)
                }
                MONITOREXIT -> {
                    val opEnd = newLabel()
                    val skipMonitorExit: Label = newLabel()
                    dup()
                    invokeBeforeLockRelease()
                    ifZCmp(GeneratorAdapter.EQ, skipMonitorExit )
                    // check whether the lock should be really released
                    monitorExit()
                    goTo(opEnd)
                    visitLabel(skipMonitorExit)
                    pop()
                    visitLabel(opEnd)
                }
                else -> visitInsn(opcode)
            }
        }

        // STACK: monitor
        private fun invokeBeforeLockAcquire() {
            invokeBeforeLockAcquireOrRelease(BEFORE_LOCK_ACQUIRE_METHOD, ::MonitorEnterCodeLocation)
        }

        // STACK: monitor
        private fun invokeBeforeLockRelease() {
            invokeBeforeLockAcquireOrRelease(BEFORE_LOCK_RELEASE_METHOD, ::MonitorExitCodeLocation)
        }

        // STACK: monitor
        private fun invokeBeforeLockAcquireOrRelease(method: Method, codeLocationConstructor: (StackTraceElement) -> CodeLocation) {
            val monitorLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(monitorLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(codeLocationConstructor)
            adapter.loadLocal(monitorLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }
    }

    /**
     * Replace "method(...) {...}" with "method(...) {synchronized(this) {...} }"
     */
    private inner class SynchronizedBlockAddingTransformer(methodName: String, mv: GeneratorAdapter, private val className: String?, access: Int, private val classVersion: Int) : ManagedStrategyMethodVisitor(methodName, mv) {
        private val isStatic: Boolean = access and ACC_STATIC != 0
        private val tryLabel = Label()
        private val catchLabel = Label()

        override fun visitCode() = adapter.run {
            super.visitCode()
            loadSynchronizedMethodMonitorOwner()
            monitorEnter()
            // note that invoking monitorEnter leads to unknown line number in the code location.
            // TODO: will invoking monitorEnter after the first visitLineNumber be correct?
            visitLabel(tryLabel)
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
            visitLabel(catchLabel)
            loadSynchronizedMethodMonitorOwner()
            monitorExit()
            throwException()
            visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null)
            visitMaxs(maxStack, maxLocals)
        }

        override fun visitInsn(opcode: Int) {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    loadSynchronizedMethodMonitorOwner()
                    adapter.monitorExit()
                }
                else -> {
                }
            }
            adapter.visitInsn(opcode)
        }

        private fun loadSynchronizedMethodMonitorOwner() = adapter.run {
            if (isStatic) {
                val classType = Type.getType("L$className;")
                if (classVersion >= V1_5) {
                    visitLdcInsn(classType)
                } else {
                    visitLdcInsn(classType.className)
                    invokeStatic(CLASS_TYPE, CLASS_FOR_NAME_METHOD)
                }
            } else {
                loadThis()
            }
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before wait and after notify calls
     */
    private inner class WaitNotifyTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            var afterWait: Label? = null
            val isWait = isWait(opcode, name, desc)
            val isNotify = isNotify(opcode, name, desc)
            if (isWait) {
                afterWait = newLabel()
                val withTimeout = desc != "()V"
                var lastArgument = 0
                var firstArgument = 0
                if (desc == "(J)V") {
                    firstArgument = newLocal(Type.LONG_TYPE)
                    storeLocal(firstArgument)
                } else if (desc == "(JI)V") {
                    lastArgument = newLocal(Type.INT_TYPE)
                    storeLocal(lastArgument)
                    firstArgument = newLocal(Type.LONG_TYPE)
                    storeLocal(firstArgument)
                }
                dup()
                invokeBeforeWait(withTimeout)
                val beforeWait: Label = newLabel()
                ifZCmp(GeneratorAdapter.GT, beforeWait)
                pop()
                goTo(afterWait)
                visitLabel(beforeWait)
                if (desc == "(J)V")
                    loadLocal(firstArgument)
                if (desc == "(JI)V") { // restore popped arguments
                    loadLocal(firstArgument)
                    loadLocal(lastArgument)
                }
            }
            if (isNotify) dup()
            visitMethodInsn(opcode, owner, name, desc, itf)
            if (isWait) visitLabel(afterWait)
            if (isNotify) {
                val notifyAll = name == "notifyAll"
                invokeAfterNotify(notifyAll)
            }
        }

        private fun isWait(opcode: Int, name: String, desc: String): Boolean {
            if (opcode == INVOKEVIRTUAL && name == "wait") {
                when (desc) {
                    "()V", "(J)V", "(JI)V" -> return true
                }
            }
            return false
        }

        private fun isNotify(opcode: Int, name: String, desc: String): Boolean {
            val isNotify = opcode == INVOKEVIRTUAL && name == "notify" && desc == "()V"
            val isNotifyAll = opcode == INVOKEVIRTUAL && name == "notifyAll" && desc == "()V"
            return isNotify || isNotifyAll
        }

        // STACK: monitor
        private fun invokeBeforeWait(withTimeout: Boolean) {
            invokeOnWaitOrNotify(BEFORE_WAIT_METHOD, withTimeout, ::WaitCodeLocation)
        }

        // STACK: monitor
        private fun invokeAfterNotify(notifyAll: Boolean) {
            invokeOnWaitOrNotify(AFTER_NOTIFY_METHOD, notifyAll, ::NotifyCodeLocation)
        }

        // STACK: monitor
        private fun invokeOnWaitOrNotify(method: Method, flag: Boolean, codeLocationConstructor: (StackTraceElement) -> CodeLocation) {
            val monitorLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(monitorLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(codeLocationConstructor)
            adapter.loadLocal(monitorLocal)
            adapter.push(flag)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before park and after unpark calls
     */
    private inner class ParkUnparkTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            val beforePark: Label = newLabel()
            val afterPark: Label = newLabel()
            val isPark = owner == "sun/misc/Unsafe" && name == "park"
            if (isPark) {
                val withoutTimeoutBranch: Label = newLabel()
                val invokeBeforeParkEnd: Label = newLabel()
                dup2()
                push(0L)
                ifCmp(Type.LONG_TYPE, GeneratorAdapter.EQ, withoutTimeoutBranch)
                push(true)
                invokeBeforePark()
                goTo(invokeBeforeParkEnd)
                visitLabel(withoutTimeoutBranch)
                push(false)
                invokeBeforePark()
                visitLabel(invokeBeforeParkEnd)
                // check whether should really park 
                ifZCmp(GeneratorAdapter.GT, beforePark) // park if returned true
                // delete park params
                pop2() // time
                pop() // isAbsolute
                pop() // Unsafe
                goTo(afterPark)
            }
            visitLabel(beforePark)
            val isUnpark = owner == "sun/misc/Unsafe" && name == "unpark"
            var threadLocal = 0
            if (isUnpark) {
                dup()
                threadLocal = newLocal(OBJECT_TYPE)
                storeLocal(threadLocal)
            }
            visitMethodInsn(opcode, owner, name, desc, itf)
            visitLabel(afterPark)
            if (isUnpark) {
                loadLocal(threadLocal)
                invokeAfterUnpark()
            }
        }

        // STACK: withTimeout
        private fun invokeBeforePark() {
            val withTimeoutLocal: Int = adapter.newLocal(Type.BOOLEAN_TYPE)
            adapter.storeLocal(withTimeoutLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(::ParkCodeLocation)
            adapter.loadLocal(withTimeoutLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_PARK_METHOD)
        }

        // STACK: thread
        private fun invokeAfterUnpark() {
            val threadLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(threadLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(::UnparkCodeLocation)
            adapter.loadLocal(threadLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_UNPARK_METHOD)
        }
    }

    /**
     * Track local objects for odd switch codeLocations elimination
     */
    private inner class LocalObjectManagingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            val isObjectCreation = opcode == INVOKESPECIAL && "<init>" == name && "java/lang/Object" == owner
            if (isObjectCreation) adapter.dup() // will be used for adding to LocalObjectManager
            adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            if (isObjectCreation) invokeOnNewLocalObject()
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            adapter.visitIntInsn(opcode, operand)
            if (opcode == NEWARRAY) {
                adapter.dup()
                invokeOnNewLocalObject()
            }
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            adapter.visitTypeInsn(opcode, type)
            if (opcode == ANEWARRAY) {
                adapter.dup()
                invokeOnNewLocalObject()
            }
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
            val isNotPrimitiveType = desc.startsWith("L") || desc.startsWith("[")
            val isFinalField = isFinalField(owner, name)
            if (isNotPrimitiveType) {
                when (opcode) {
                    PUTSTATIC -> {
                        adapter.dup()
                        invokeDeleteLocalObject()
                    }
                    PUTFIELD -> {
                        // we cannot invoke this method for final field, because an object may uninitialized yet
                        // will add dependency for final fields after <init> ends instead
                        if (!isFinalField) {
                            // owner, value
                            adapter.dup2() // owner, value, owner, value
                            invokeAddDependency() // owner, value
                        }
                    }
                }
            }
            super.visitFieldInsn(opcode, owner, name, desc)
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            val value: Int
            when (opcode) {
                AASTORE -> {
                    // array, index, value
                    value = newLocal(OBJECT_TYPE)
                    storeLocal(value) // array, index
                    dup2() // array, index, array, index
                    pop() // array, index, array
                    loadLocal(value) // array, index, array, value
                    invokeAddDependency() // array, index
                    loadLocal(value) // array, index, value
                }
                RETURN -> if (methodName == "<init>") {
                    // handle all final field added dependencies
                    val ownerType = Type.getObjectType(className)
                    for (field in getNonStaticFinalFields(className)) {
                        if (field.type.isPrimitive) continue
                        val fieldType = Type.getType(field.type)
                        loadThis() // owner
                        loadThis() // owner, owner
                        getField(ownerType, field.name, fieldType) // owner, value
                        invokeAddDependency()
                    }
                }
            }
            adapter.visitInsn(opcode)
        }

        // STACK: object
        private fun invokeOnNewLocalObject() {
            val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(objectLocal)
            loadLocalObjectManager()
            adapter.loadLocal(objectLocal)
            adapter.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, NEW_LOCAL_OBJECT_METHOD)
        }

        // STACK: object
        private fun invokeDeleteLocalObject() {
            val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(objectLocal)
            loadLocalObjectManager()
            adapter.loadLocal(objectLocal)
            adapter.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, DELETE_LOCAL_OBJECT_METHOD)
        }

        // STACK: owner, dependant
        private fun invokeAddDependency() {
            val ownerLocal: Int = adapter.newLocal(OBJECT_TYPE)
            val dependantLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(dependantLocal)
            adapter.storeLocal(ownerLocal)
            loadLocalObjectManager()
            adapter.loadLocal(ownerLocal)
            adapter.loadLocal(dependantLocal)
            adapter.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, ADD_DEPENDENCY_METHOD)
        }
    }

    private open inner class ManagedStrategyMethodVisitor(protected val methodName: String, val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        private var lineNumber = 0

        protected fun invokeBeforeIgnoredSectionEntering() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, ENTER_IGNORED_SECTION_METHOD)
        }

        protected fun invokeAfterIgnoredSectionLeaving() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, LEAVE_IGNORED_SECTION_METHOD)
        }

        protected fun invokeMakeStateRepresentation() {
            if (shouldMakeStateRepresentation) {
                loadStrategy()
                loadCurrentThreadNumber()
                adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, MAKE_STATE_REPRESENTATION_METHOD)
            }
        }

        protected fun loadStrategy() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStateHolder::strategy.name, MANAGED_STRATEGY_TYPE)
        }

        protected fun loadLocalObjectManager() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStateHolder::objectManager.name, LOCAL_OBJECT_MANAGER_TYPE)
        }

        protected fun loadCurrentThreadNumber() {
            loadStrategy()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, CURRENT_THREAD_NUMBER_METHOD)
        }

        protected fun loadNewCodeLocation(codeLocationConstructor: (StackTraceElement) -> CodeLocation) {
            val codeLocation = codeLocations.size
            codeLocations.add(codeLocationConstructor(StackTraceElement(className, methodName, fileName, lineNumber)))
            adapter.push(codeLocation)
        }

        override fun visitLineNumber(line: Int, start: Label) {
            lineNumber = line
            super.visitLineNumber(line, start)
        }
    }

    companion object {
        private val OBJECT_TYPE = Type.getType(Any::class.java)
        private val MANAGED_STATE_HOLDER_TYPE = Type.getType(ManagedStateHolder::class.java)
        private val MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy::class.java)
        private val LOCAL_OBJECT_MANAGER_TYPE = Type.getType(LocalObjectManager::class.java)
        private val RANDOM_TYPE = Type.getType(Random::class.java)
        private val UNSAFE_HOLDER_TYPE = Type.getType(UnsafeHolder::class.java)
        private val STRING_TYPE = Type.getType(String::class.java)
        private val CLASS_TYPE = Type.getType(Class::class.java)
        private val OBJECT_ARRAY_TYPE = Type.getType("[" + OBJECT_TYPE.descriptor)
        private val WRITE_CODELOCATION_TYPE = Type.getType(WriteCodeLocation::class.java)
        private val READ_CODELOCATION_TYPE = Type.getType(ReadCodeLocation::class.java)
        private val METHOD_CALL_CODELOCATION_TYPE = Type.getType(MethodCallCodeLocation::class.java)

        private val CURRENT_THREAD_NUMBER_METHOD = Method.getMethod(ManagedStrategy::currentThreadNumber.javaMethod)
        private val BEFORE_SHARED_VARIABLE_READ_METHOD = Method.getMethod(ManagedStrategy::beforeSharedVariableRead.javaMethod)
        private val BEFORE_SHARED_VARIABLE_WRITE_METHOD = Method.getMethod(ManagedStrategy::beforeSharedVariableWrite.javaMethod)
        private val BEFORE_LOCK_ACQUIRE_METHOD = Method.getMethod(ManagedStrategy::beforeLockAcquire.javaMethod)
        private val BEFORE_LOCK_RELEASE_METHOD = Method.getMethod(ManagedStrategy::beforeLockRelease.javaMethod)
        private val BEFORE_WAIT_METHOD = Method.getMethod(ManagedStrategy::beforeWait.javaMethod)
        private val AFTER_NOTIFY_METHOD = Method.getMethod(ManagedStrategy::afterNotify.javaMethod)
        private val BEFORE_PARK_METHOD = Method.getMethod(ManagedStrategy::beforePark.javaMethod)
        private val AFTER_UNPARK_METHOD = Method.getMethod(ManagedStrategy::afterUnpark.javaMethod)
        private val ENTER_IGNORED_SECTION_METHOD = Method.getMethod(ManagedStrategy::enterIgnoredSection.javaMethod)
        private val LEAVE_IGNORED_SECTION_METHOD = Method.getMethod(ManagedStrategy::leaveIgnoredSection.javaMethod)
        private val BEFORE_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::beforeMethodCall.javaMethod)
        private val AFTER_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::afterMethodCall.javaMethod)
        private val MAKE_STATE_REPRESENTATION_METHOD = Method.getMethod(ManagedStrategy::makeStateRepresentation.javaMethod)
        private val BEFORE_ATOMIC_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::beforeAtomicMethodCall.javaMethod)
        private val GET_CODELOCATION_DESCRIPTION_METHOD = Method.getMethod(ManagedStrategy::getLocationDescription.javaMethod)
        private val NEW_LOCAL_OBJECT_METHOD = Method.getMethod(LocalObjectManager::newLocalObject.javaMethod)
        private val DELETE_LOCAL_OBJECT_METHOD = Method.getMethod(LocalObjectManager::deleteLocalObject.javaMethod)
        private val IS_LOCAL_OBJECT_METHOD = Method.getMethod(LocalObjectManager::isLocalObject.javaMethod)
        private val ADD_DEPENDENCY_METHOD = Method.getMethod(LocalObjectManager::addDependency.javaMethod)
        private val GET_UNSAFE_METHOD = Method.getMethod(UnsafeHolder::getUnsafe.javaMethod)
        private val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE)) // manual, because there are several forName methods
        private val ADD_WRITTEN_VALUE_METHOD = Method.getMethod(WriteCodeLocation::addWrittenValue.javaMethod)
        private val ADD_READ_VALUE_METHOD = Method.getMethod(ReadCodeLocation::addReadValue.javaMethod)
        private val ADD_RETURNED_VALUE_METHOD = Method.getMethod(MethodCallCodeLocation::addReturnedValue.javaMethod)
        private val ADD_PARAMETERS_METHOD = Method.getMethod(MethodCallCodeLocation::addParameters.javaMethod)

        /**
         * Returns array of locals containing given parameters.
         * STACK: param_1 param_2 ... param_n
         * RESULT STACK: (empty)
         */
        private fun GeneratorAdapter.storeParameters(paramTypes: Array<Type>): IntArray {
            val locals = IntArray(paramTypes.size)
            // store all arguments
            for (i in paramTypes.indices.reversed()) {
                locals[i] = newLocal(paramTypes[i])
                storeLocal(locals[i], paramTypes[i])
            }
            return locals
        }

        private fun GeneratorAdapter.storeParameters(methodDescriptor: String) = storeParameters(Type.getArgumentTypes(methodDescriptor))

        /**
         * Returns array of locals containing given parameters.
         * STACK: param_1 param_2 ... param_n
         * RESULT STACK: param_1 param_2 ... param_n (the stack is not changed)
         */
        private fun GeneratorAdapter.copyParameters(paramTypes: Array<Type>): IntArray {
            val locals = storeParameters(paramTypes)
            loadLocals(locals)
            return locals
        }

        private fun GeneratorAdapter.loadLocals(locals: IntArray) {
            for (local in locals)
                loadLocal(local)
        }

        /**
         * Saves the top value on the stack without changing stack.
         */
        private fun GeneratorAdapter.copyLocal(local: Int) {
            storeLocal(local)
            loadLocal(local)
        }

        /**
         * Get non-static final fields that belong to the class. Note that final fields of super classes won't be returned.
         */
        private fun getNonStaticFinalFields(ownerInternal: String): List<Field> {
            var ownerInternal = ownerInternal
            if (ownerInternal.startsWith(TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME)) {
                ownerInternal = ownerInternal.substring(TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME.length)
            }
            return try {
                val clazz = Class.forName(ownerInternal.replace('/', '.'))
                val fields = clazz.declaredFields
                Arrays.stream(fields)
                        .filter { field: Field -> field.modifiers and (Modifier.FINAL or Modifier.STATIC) == Modifier.FINAL }
                        .collect(Collectors.toList())
            } catch (e: ClassNotFoundException) {
                throw RuntimeException(e)
            }
        }

        private fun isFinalField(ownerInternal: String, fieldName: String): Boolean {
            var internalName = ownerInternal
            if (internalName.startsWith(TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME)) {
                internalName = internalName.substring(TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME.length)
            }
            return try {
                val clazz = Class.forName(internalName.replace('/', '.'))
                findField(clazz, fieldName).modifiers and Modifier.FINAL == Modifier.FINAL
            } catch (e: ClassNotFoundException) {
                throw RuntimeException(e)
            } catch (e: NoSuchFieldException) {
                throw RuntimeException(e)
            }
        }

        private fun findField(clazz: Class<*>, fieldName: String): Field {
            var clazz: Class<*>? = clazz
            do {
                val fields = clazz!!.declaredFields
                for (field in fields) if (field.name == fieldName) return field
                clazz = clazz.superclass
            } while (clazz != null)
            throw NoSuchFieldException()
        }
    }
}