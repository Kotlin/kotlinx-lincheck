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

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME
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
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod

/**
 * This transformer inserts [ManagedStrategy] methods invocations.
 */
internal class ManagedStrategyTransformer(
    cv: ClassVisitor?,
    private val codeLocationsConstructors: MutableList<(() -> CodePoint)?>,
    private val guarantees: List<ManagedStrategyGuarantee>,
    private val shouldMakeStateRepresentation: Boolean,
    private val shouldEliminateLocalObjects: Boolean,
    private val loggingEnabled: Boolean
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
        mv = TryCatchBlockSorter(mv, access, mname, desc, signature, exceptions)
        mv = SynchronizedBlockTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        if (isSynchronized) {
            // synchronized method is replaced with synchronized lock
            mv = SynchronizedBlockAddingTransformer(mname, GeneratorAdapter(mv, access, mname, desc), className, access, classVersion)
        }
        mv = ClassInitializationTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        if (loggingEnabled)
            mv = AFUTrackingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ManagedStrategyGuaranteeTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = CallStackTraceLoggingTransformer(className, mname, GeneratorAdapter(mv, access, mname, desc))
        mv = HashCodeStubTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = UnsafeTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = WaitNotifyTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ParkUnparkTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = LocalObjectManagingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = CancellabilitySupportMethodTransformer(className, mname, GeneratorAdapter(mv, access, mname, desc))
        mv = SharedVariableAccessMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = TimeStubTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = RandomTransformer(GeneratorAdapter(mv, access, mname, desc))
        return mv
    }

    /**
     * Changes package of transformed classes from `java.util` package, excluding some,
     * because transformed classes can not lie in `java.util`
     */
    internal class JavaUtilRemapper : Remapper() {
        override fun map(name: String): String {
            // remap `java.util` package
            if (name.startsWith("java/util/")) {
                val normalizedName = name.toClassName()
                // transformation of exceptions causes a lot of trouble with catching expected exceptions
                val isException = Throwable::class.java.isAssignableFrom(Class.forName(normalizedName))
                // function package is not transformed, because AFU uses it, and thus, there will be transformation problems
                val inFunctionPackage = name.startsWith("java/util/function/")
                val isImpossibleToTransformPrimitive = isImpossibleToTransformPrimitive(normalizedName)
                if (!isImpossibleToTransformPrimitive && !isException && !inFunctionPackage)
                    return TRANSFORMED_PACKAGE_INTERNAL_NAME + name
            }
            // remap java.lang.Iterable, because its only method returns an object from java.util package
            // that will not work if not transformed
            if (name == "java/lang/Iterable") return TRANSFORMED_PACKAGE_INTERNAL_NAME + name;
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
            if (isFinalField(owner, name) || isTrustedPrimitive(owner.toClassName()) || isSuspendStateMachine(owner) || isStrategyCall(owner)) {
                super.visitFieldInsn(opcode, owner, name, desc)
                return
            }

            when (opcode) {
                GETSTATIC -> {
                    val codePointLocal = newCodePointLocal()
                    invokeBeforeSharedVariableRead(name, codePointLocal)
                    super.visitFieldInsn(opcode, owner, name, desc)
                    captureReadValue(desc, codePointLocal)
                }
                GETFIELD -> {
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = newLabel()
                    val codePointLocal = newCodePointLocal()
                    dup()
                    invokeIsLocalObject()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    invokeBeforeSharedVariableRead(name, codePointLocal)
                    visitLabel(skipCodeLocationBefore)

                    super.visitFieldInsn(opcode, owner, name, desc)

                    val skipCodeLocationAfter = newLabel()
                    loadLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationAfter)
                    // initialize ReadCodeLocation only if is not a local object
                    captureReadValue(desc, codePointLocal)
                    visitLabel(skipCodeLocationAfter)
                }
                PUTSTATIC -> {
                    beforeSharedVariableWrite(name, desc)
                    super.visitFieldInsn(opcode, owner, name, desc)
                    invokeMakeStateRepresentation()
                }
                PUTFIELD -> {
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = newLabel()
                    dupOwnerOnPutField(desc)
                    invokeIsLocalObject()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    beforeSharedVariableWrite(name, desc)
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
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = adapter.newLabel()
                    val codePointLocal = newCodePointLocal()
                    dup2() // arr, ind
                    pop() // arr, ind -> arr
                    invokeIsLocalObject()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    invokeBeforeSharedVariableRead(null, codePointLocal)
                    visitLabel(skipCodeLocationBefore)

                    super.visitInsn(opcode)

                    val skipCodeLocationAfter = newLabel()
                    loadLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationAfter)
                    captureReadValue(getArrayLoadType(opcode).descriptor, codePointLocal)
                    visitLabel(skipCodeLocationAfter)
                }
                AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                    val isLocalObject = newLocal(Type.BOOLEAN_TYPE)
                    val skipCodeLocationBefore = adapter.newLabel()
                    dupArrayOnArrayStore(opcode)
                    invokeIsLocalObject()
                    copyLocal(isLocalObject)
                    ifZCmp(GeneratorAdapter.GT, skipCodeLocationBefore)
                    // add strategy invocation only if is not a local object
                    beforeSharedVariableWrite(null, getArrayStoreType(opcode).descriptor)
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
        private fun captureReadValue(desc: String, codePointLocal: Int?) = adapter.run {
            if (!loggingEnabled) return // capture return values only when logging is enabled
            val valueType = Type.getType(desc)
            val readValue = newLocal(valueType)
            copyLocal(readValue)
            // initialize ReadCodeLocation
            loadStrategy()
            loadLocal(codePointLocal!!)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODE_POINT_METHOD)
            checkCast(READ_CODE_POINT_TYPE)
            loadLocal(readValue)
            box(valueType)
            invokeVirtual(READ_CODE_POINT_TYPE, INITIALIZE_READ_VALUE_METHOD)
        }

        // STACK: value to be written
        private fun beforeSharedVariableWrite(fieldName: String? = null, desc: String) {
            val codePointLocal = newCodePointLocal()
            invokeBeforeSharedVariableWrite(fieldName, codePointLocal)
            captureWrittenValue(desc, codePointLocal)
        }

        // STACK: value to be written
        private fun captureWrittenValue(desc: String, codePointLocal: Int?) = adapter.run {
            if (!loggingEnabled) return // capture written values only when logging is enabled
            val valueType = Type.getType(desc)
            val storedValue = newLocal(valueType)
            copyLocal(storedValue) // save store value
            // initialize WriteCodeLocation with stored value
            loadStrategy()
            loadLocal(codePointLocal!!)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODE_POINT_METHOD)
            checkCast(WRITE_CODE_POINT_TYPE)
            loadLocal(storedValue)
            box(valueType)
            invokeVirtual(WRITE_CODE_POINT_TYPE, INITIALIZE_WRITTEN_VALUE_METHOD)
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

        private fun getArrayLoadType(opcode: Int): Type = when (opcode) {
            AALOAD -> OBJECT_TYPE
            IALOAD -> Type.INT_TYPE
            FALOAD -> Type.FLOAT_TYPE
            BALOAD -> Type.BOOLEAN_TYPE
            CALOAD -> Type.CHAR_TYPE
            SALOAD -> Type.SHORT_TYPE
            LALOAD -> Type.LONG_TYPE
            DALOAD -> Type.DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }

        private fun invokeBeforeSharedVariableRead(fieldName: String? = null, codePointLocal: Int?) =
            invokeBeforeSharedVariableReadOrWrite(BEFORE_SHARED_VARIABLE_READ_METHOD, codePointLocal) { ste -> ReadCodePoint(fieldName, ste)}

        private fun invokeBeforeSharedVariableWrite(fieldName: String? = null, codePointLocal: Int?) =
            invokeBeforeSharedVariableReadOrWrite(BEFORE_SHARED_VARIABLE_WRITE_METHOD, codePointLocal) { ste -> WriteCodePoint(fieldName, ste)}

        private fun invokeBeforeSharedVariableReadOrWrite(method: Method, codePointLocal: Int?, codeLocationConstructor: (StackTraceElement) -> CodePoint) {
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(codePointLocal, codeLocationConstructor)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }

        // STACK: object
        private fun invokeIsLocalObject() {
            if (shouldEliminateLocalObjects) {
                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(objectLocal)
                loadObjectManager()
                adapter.loadLocal(objectLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, IS_LOCAL_OBJECT_METHOD)
            } else {
                adapter.pop()
                adapter.push(false)
            }
        }
    }

    /**
     * Add strategy method invocations corresponding to ManagedGuarantee guarantees.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     */
    private inner class ManagedStrategyGuaranteeTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
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
                if (guarantee.methodPredicate(methodName) && guarantee.classPredicate(className.toClassName()))
                    return guarantee.type
            return null
        }

        private fun invokeBeforeAtomicMethodCall() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.push(codeLocationsConstructors.lastIndex) // re-use code location
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
    private inner class CallStackTraceLoggingTransformer(className: String, methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        private val isSuspendStateMachine by lazy { isSuspendStateMachine(className) }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            if (isSuspendStateMachine || isStrategyCall(owner) || isInternalCoroutineCall(owner, name)) {
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            if (!loggingEnabled) {
                // just add null to increase code location id
                codeLocationsConstructors.add(null)
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            val callStart = newLabel()
            val callEnd = newLabel()
            val exceptionHandler = newLabel()
            val skipHandler = newLabel()

            val codePointLocal = newLocal(Type.INT_TYPE)
            beforeMethodCall(opcode, owner, name, desc, codePointLocal)
            if (name != "<init>") {
                // just hope that constructors do not throw exceptions.
                // we can not handle this case, because uninitialized values are not allowed by jvm
                visitTryCatchBlock(callStart, callEnd, exceptionHandler, null)
            }
            visitLabel(callStart)
            visitMethodInsn(opcode, owner, name, desc, itf)
            visitLabel(callEnd)
            afterMethodCall(Method(name, desc).returnType, codePointLocal)

            goTo(skipHandler)
            visitLabel(exceptionHandler)
            onException(codePointLocal)
            invokeAfterMethodCall(codePointLocal) // notify strategy that the method finished
            throwException() // throw the exception further
            visitLabel(skipHandler)
        }

        // STACK: param_1 param_2 ... param_n
        private fun beforeMethodCall(opcode: Int, owner: String, methodName: String, desc: String, codePointLocal: Int) {
            invokeBeforeMethodCall(methodName, codePointLocal)
            captureParameters(opcode, owner, methodName, desc, codePointLocal)
            captureOwnerName(opcode, owner, methodName, desc, codePointLocal)
        }

        // STACK: returned value (unless void)
        private fun afterMethodCall(returnType: Type, codePointLocal: Int) = adapter.run {
            if (returnType != Type.VOID_TYPE) {
                val returnedValue = newLocal(returnType)
                copyLocal(returnedValue)
                // initialize MethodCallCodePoint return value
                loadStrategy()
                loadLocal(codePointLocal)
                invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODE_POINT_METHOD)
                checkCast(METHOD_CALL_CODE_POINT_TYPE)
                loadLocal(returnedValue)
                box(returnType)
                invokeVirtual(METHOD_CALL_CODE_POINT_TYPE, INITIALIZE_RETURNED_VALUE_METHOD)
            }
            invokeAfterMethodCall(codePointLocal)
        }

        // STACK: exception
        private fun onException(codePointLocal: Int) = adapter.run {
            val exceptionLocal = newLocal(THROWABLE_TYPE)
            copyLocal(exceptionLocal)
            // initialize MethodCallCodePoint thrown exception
            loadStrategy()
            loadLocal(codePointLocal)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODE_POINT_METHOD)
            checkCast(METHOD_CALL_CODE_POINT_TYPE)
            loadLocal(exceptionLocal)
            invokeVirtual(METHOD_CALL_CODE_POINT_TYPE, INITIALIZE_THROWN_EXCEPTION_METHOD)
        }

        // STACK: param_1 param_2 ... param_n
        private fun captureParameters(opcode: Int, owner: String, methodName: String, desc: String, codePointLocal: Int) = adapter.run {
            val paramTypes = Type.getArgumentTypes(desc)
            if (paramTypes.isEmpty()) return // nothing to capture
            val params = copyParameters(paramTypes)
            val firstLoggedParameter = if (isAFUMethodCall(opcode, owner, methodName, desc)) {
                // do not log the first object in AFU methods
                1
            } else {
                0
            }
            val lastLoggedParameter = if (paramTypes.last().internalName == "kotlin/coroutines/Continuation" && isSuspend(owner, methodName, desc)) {
                // do not log the last continuation in suspend functions
                paramTypes.size - 1
            } else {
                paramTypes.size
            }
            // create array of parameters
            push(lastLoggedParameter - firstLoggedParameter) // size of the array
            visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
            val parameterValuesLocal = newLocal(OBJECT_ARRAY_TYPE)
            storeLocal(parameterValuesLocal)
            for (i in firstLoggedParameter until lastLoggedParameter) {
                loadLocal(parameterValuesLocal)
                push(i - firstLoggedParameter)
                loadLocal(params[i])
                box(paramTypes[i]) // in case it is a primitive type
                arrayStore(OBJECT_TYPE)
            }
            // initialize MethodCallCodePoint parameter values
            loadStrategy()
            loadLocal(codePointLocal)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODE_POINT_METHOD)
            checkCast(METHOD_CALL_CODE_POINT_TYPE)
            loadLocal(parameterValuesLocal)
            invokeVirtual(METHOD_CALL_CODE_POINT_TYPE, INITIALIZE_PARAMETERS_METHOD)
        }

        // STACK: owner param_1 param_2 ... param_n
        private fun captureOwnerName(opcode: Int, owner: String, methodName: String, desc: String, codePointLocal: Int) = adapter.run {
            if (!isAFUMethodCall(opcode, owner, methodName, desc)) {
                // currently object name labels are used only for AFUs
                return
            }
            val afuLocal = newLocal(Type.getType("L$owner;"))
            // temporarily remove parameters from stack to copy AFU
            val params = storeParameters(desc)
            copyLocal(afuLocal)
            // return parameters to the stack
            for (param in params)
                loadLocal(param)
            // initialize MethodCallCodePoint owner name
            loadStrategy()
            loadLocal(codePointLocal)
            invokeVirtual(MANAGED_STRATEGY_TYPE, GET_CODE_POINT_METHOD)
            checkCast(METHOD_CALL_CODE_POINT_TYPE)
            // get afu name
            loadObjectManager()
            loadLocal(afuLocal)
            invokeVirtual(OBJECT_MANAGER_TYPE, GET_OBJECT_NAME)
            invokeVirtual(METHOD_CALL_CODE_POINT_TYPE, INITIALIZE_OWNER_NAME_METHOD)
        }

        private fun invokeBeforeMethodCall(methodName: String, codePointLocal: Int) {
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(codePointLocal) { ste -> MethodCallCodePoint(methodName, ste) }
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_METHOD_CALL_METHOD)
        }

        private fun invokeAfterMethodCall(codePointLocal: Int) {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.loadLocal(codePointLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_METHOD_CALL_METHOD)
        }

        private fun isInternalCoroutineCall(owner: String, name: String) =
            owner == "kotlin/coroutines/intrinsics/IntrinsicsKt" && name == "getCOROUTINE_SUSPENDED"
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
                adapter.checkCast(UNSAFE_TYPE)
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Tracks names of fields for created AFUs and saves them via ObjectManager.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     */
    private inner class AFUTrackingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, mname: String, desc: String, isInterface: Boolean) = adapter.run {
            val isAFUCreation = opcode == INVOKESTATIC && mname == "newUpdater" && isAFU(owner)
            when {
                isAFUCreation -> {
                    val nameLocal = newLocal(STRING_TYPE)
                    copyLocal(nameLocal) // name is the last parameter
                    visitMethodInsn(opcode, owner, mname, desc, isInterface)
                    val afuLocal = newLocal(Type.getType("L$owner;"))
                    copyLocal(afuLocal) // copy AFU
                    loadObjectManager()
                    loadLocal(afuLocal)
                    loadLocal(nameLocal)
                    invokeVirtual(OBJECT_MANAGER_TYPE, REGISTER_OBJECT_NAME)
                }
                else -> visitMethodInsn(opcode, owner, mname, desc, isInterface)
            }
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
        private val randomMethods by lazy { Random::class.java.declaredMethods }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (opcode == INVOKEVIRTUAL && extendsRandom(owner.toClassName()) && isRandomMethod(name, desc)) {
                val locals = adapter.storeParameters(desc)
                adapter.pop() // pop replaced Random
                loadRandom()
                adapter.loadLocals(locals)
                adapter.visitMethodInsn(opcode, "java/util/Random", name, desc, itf)
                return
            }
            // there are also static methods in ThreadLocalRandom that are used inside java.util.concurrent.
            // they are replaced with nextInt method.
            val isThreadLocalRandomMethod = owner == "java/util/concurrent/ThreadLocalRandom"
            if (isThreadLocalRandomMethod && (name == "nextSecondarySeed" || name == "getProbe")) {
                loadRandom()
                adapter.invokeVirtual(RANDOM_TYPE, NEXT_INT_METHOD)
                return
            }
            if (isThreadLocalRandomMethod && name == "advanceProbe") {
                adapter.pop() // pop parameter
                loadRandom()
                adapter.invokeVirtual(RANDOM_TYPE, NEXT_INT_METHOD)
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
            invokeBeforeLockAcquireOrRelease(BEFORE_LOCK_ACQUIRE_METHOD, ::MonitorEnterCodePoint)
        }

        // STACK: monitor
        private fun invokeBeforeLockRelease() {
            invokeBeforeLockAcquireOrRelease(BEFORE_LOCK_RELEASE_METHOD, ::MonitorExitCodePoint)
        }

        // STACK: monitor
        private fun invokeBeforeLockAcquireOrRelease(method: Method, codeLocationConstructor: (StackTraceElement) -> CodePoint) {
            val monitorLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(monitorLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(null, codeLocationConstructor)
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
            // note that invoking monitorEnter here leads to unknown line number in the code location.
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
            invokeOnWaitOrNotify(BEFORE_WAIT_METHOD, withTimeout, ::WaitCodePoint)
        }

        // STACK: monitor
        private fun invokeAfterNotify(notifyAll: Boolean) {
            invokeOnWaitOrNotify(AFTER_NOTIFY_METHOD, notifyAll, ::NotifyCodePoint)
        }

        // STACK: monitor
        private fun invokeOnWaitOrNotify(method: Method, flag: Boolean, codeLocationConstructor: (StackTraceElement) -> CodePoint) {
            val monitorLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(monitorLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(null, codeLocationConstructor)
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
            loadNewCodeLocation(null, ::ParkCodePoint)
            adapter.loadLocal(withTimeoutLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_PARK_METHOD)
        }

        // STACK: thread
        private fun invokeAfterUnpark() {
            val threadLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(threadLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocation(null, ::UnparkCodePoint)
            adapter.loadLocal(threadLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_UNPARK_METHOD)
        }
    }

    /**
     * Removes switch points in CancellableContinuationImpl.cancel, so that they will not be reported
     * when a continuation is cancelled by lincheck
     */
    private inner class CancellabilitySupportMethodTransformer(className: String, methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        private val isCancel = className == "kotlinx/coroutines/CancellableContinuationImpl" && methodName == "cancel"

        override fun visitCode() {
            if (isCancel)
                invokeBeforeIgnoredSectionEntering()
            mv.visitCode()
        }

        override fun visitInsn(opcode: Int) {
            if (isCancel) {
                when (opcode) {
                    ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> invokeAfterIgnoredSectionLeaving()
                    else -> { }
                }
            }
            mv.visitInsn(opcode)
        }
    }

    /**
     * Track local objects for odd switch points elimination.
     * A local object is an object that can be possible viewed only from one thread.
     */
    private inner class LocalObjectManagingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            val isObjectCreation = opcode == INVOKESPECIAL && name == "<init>" && owner == "java/lang/Object"
            val isImpossibleToTransformPrimitive = isImpossibleToTransformPrimitive(owner.toClassName())
            val lowerCaseName = name.toLowerCase()
            val isPrimitiveWrite = isImpossibleToTransformPrimitive && WRITE_KEYWORDS.any { it in lowerCaseName }
            val isObjectPrimitiveWrite = isPrimitiveWrite && Type.getArgumentTypes(descriptor).lastOrNull()?.descriptor?.isNotPrimitiveType() ?: false

            when {
                isObjectCreation -> {
                    adapter.dup() // will be used for onNewLocalObject method
                    adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    invokeOnNewLocalObject()
                }
                isObjectPrimitiveWrite -> {
                    // the exact list of methods that should be matched here:
                    // Unsafe.put[Ordered]?Object[Volatile]?
                    // Unsafe.getAndSet
                    // Unsafe.compareAndSwapObject
                    // VarHandle.set[Volatile | Acquire | Opaque]?
                    // VarHandle.[weak]?CompareAndSet[Plain | Acquire | Release]?
                    // VarHandle.compareAndExchange[Acquire | Release]?
                    // VarHandle.getAndSet[Acquire | Release]?
                    // AtomicReferenceFieldUpdater.compareAndSet
                    // AtomicReferenceFieldUpdater.[lazy]?Set
                    // AtomicReferenceFieldUpdater.getAndSet

                    // all this methods have the field owner as the first argument and the written value as the last one
                    val params = adapter.copyParameters(descriptor)
                    adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    adapter.loadLocal(params.first())
                    adapter.loadLocal(params.last())
                    invokeAddDependency()
                }
                else -> adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
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
            val isNotPrimitiveType = desc.isNotPrimitiveType()
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
            if (shouldEliminateLocalObjects) {
                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(objectLocal)
                loadObjectManager()
                adapter.loadLocal(objectLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, NEW_LOCAL_OBJECT_METHOD)
            } else {
                adapter.pop()
            }
        }

        // STACK: object
        private fun invokeDeleteLocalObject() {
            if (shouldEliminateLocalObjects) {
                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(objectLocal)
                loadObjectManager()
                adapter.loadLocal(objectLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, DELETE_LOCAL_OBJECT_METHOD)
            } else {
                adapter.pop()
            }
        }

        // STACK: owner, dependant
        private fun invokeAddDependency() {
            if (shouldEliminateLocalObjects) {
                val ownerLocal: Int = adapter.newLocal(OBJECT_TYPE)
                val dependantLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(dependantLocal)
                adapter.storeLocal(ownerLocal)
                loadObjectManager()
                adapter.loadLocal(ownerLocal)
                adapter.loadLocal(dependantLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, ADD_DEPENDENCY_METHOD)
            } else {
                repeat(2) { adapter.pop() }
            }
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
            if (shouldMakeStateRepresentation && loggingEnabled) {
                loadStrategy()
                loadCurrentThreadNumber()
                adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, MAKE_STATE_REPRESENTATION_METHOD)
            }
        }

        protected fun loadStrategy() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStateHolder::strategy.name, MANAGED_STRATEGY_TYPE)
        }

        protected fun loadObjectManager() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStateHolder::objectManager.name, OBJECT_MANAGER_TYPE)
        }

        protected fun loadCurrentThreadNumber() {
            loadStrategy()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, CURRENT_THREAD_NUMBER_METHOD)
        }

        protected fun loadNewCodeLocation(codePointLocal: Int?, codeLocationConstructor: (StackTraceElement) -> CodePoint) {
            if (loggingEnabled) {
                // add constructor for the code location
                val className = className  // for capturing by value in lambda constructor
                val fileName = fileName
                val lineNumber = lineNumber
                codeLocationsConstructors.add { codeLocationConstructor(StackTraceElement(className, methodName, fileName, lineNumber)) }
                // invoke the constructor
                loadStrategy()
                adapter.push(codeLocationsConstructors.lastIndex)
                adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, CREATE_CODE_POINT_METHOD)
                // the id of created code location is either stored to codePointLocal or popped
                if (codePointLocal != null)
                    adapter.storeLocal(codePointLocal)
                else
                    adapter.pop()
            } else {
                // code locations are not used when logging is disabled
                // just add null, because size of codeLocationsConstructors helps to numerate code locations
                codeLocationsConstructors.add(null)
            }
            adapter.push(codeLocationsConstructors.lastIndex)
        }

        protected fun newCodePointLocal(): Int? =
            if (loggingEnabled) {
                val codePointLocal = adapter.newLocal(Type.INT_TYPE)
                // initialize codePointLocal, because otherwise transformed code such as
                // if (b) write(local, value)
                // if (b) read(local)
                // causes bytecode verification exception
                adapter.push(0)
                adapter.storeLocal(codePointLocal)
                codePointLocal
            } else {
                // code locations are not used without logging enabled, so just return null
                null
            }

        override fun visitLineNumber(line: Int, start: Label) {
            lineNumber = line
            super.visitLineNumber(line, start)
        }
    }

    companion object {
        private val OBJECT_TYPE = Type.getType(Any::class.java)
        private val THROWABLE_TYPE = Type.getType(java.lang.Throwable::class.java)
        private val MANAGED_STATE_HOLDER_TYPE = Type.getType(ManagedStateHolder::class.java)
        private val MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy::class.java)
        private val OBJECT_MANAGER_TYPE = Type.getType(ObjectManager::class.java)
        private val RANDOM_TYPE = Type.getType(Random::class.java)
        private val UNSAFE_HOLDER_TYPE = Type.getType(UnsafeHolder::class.java)
        private val STRING_TYPE = Type.getType(String::class.java)
        private val CLASS_TYPE = Type.getType(Class::class.java)
        private val OBJECT_ARRAY_TYPE = Type.getType("[" + OBJECT_TYPE.descriptor)
        private val UNSAFE_TYPE = Type.getType("Lsun/misc/Unsafe;") // no direct referencing to allow compiling with jdk9+
        private val WRITE_CODE_POINT_TYPE = Type.getType(WriteCodePoint::class.java)
        private val READ_CODE_POINT_TYPE = Type.getType(ReadCodePoint::class.java)
        private val METHOD_CALL_CODE_POINT_TYPE = Type.getType(MethodCallCodePoint::class.java)

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
        private val GET_CODE_POINT_METHOD = Method.getMethod(ManagedStrategy::getCodePoint.javaMethod)
        private val CREATE_CODE_POINT_METHOD = Method.getMethod(ManagedStrategy::createCodePoint.javaMethod)
        private val NEW_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::newLocalObject.javaMethod)
        private val DELETE_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::deleteLocalObject.javaMethod)
        private val IS_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::isLocalObject.javaMethod)
        private val ADD_DEPENDENCY_METHOD = Method.getMethod(ObjectManager::addDependency.javaMethod)
        private val REGISTER_OBJECT_NAME = Method.getMethod(ObjectManager::registerObjectName.javaMethod)
        private val GET_OBJECT_NAME = Method.getMethod(ObjectManager::getObjectName.javaMethod)
        private val GET_UNSAFE_METHOD = Method.getMethod(UnsafeHolder::getUnsafe.javaMethod)
        private val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE)) // manual, because there are several forName methods
        private val INITIALIZE_WRITTEN_VALUE_METHOD = Method.getMethod(WriteCodePoint::initializeWrittenValue.javaMethod)
        private val INITIALIZE_READ_VALUE_METHOD = Method.getMethod(ReadCodePoint::initializeReadValue.javaMethod)
        private val INITIALIZE_RETURNED_VALUE_METHOD = Method.getMethod(MethodCallCodePoint::initializeReturnedValue.javaMethod)
        private val INITIALIZE_THROWN_EXCEPTION_METHOD = Method.getMethod(MethodCallCodePoint::initializeThrownException.javaMethod)
        private val INITIALIZE_PARAMETERS_METHOD = Method.getMethod(MethodCallCodePoint::initializeParameters.javaMethod)
        private val INITIALIZE_OWNER_NAME_METHOD = Method.getMethod(MethodCallCodePoint::initializeOwnerName.javaMethod)
        private val NEXT_INT_METHOD = Method("nextInt", Type.INT_TYPE, emptyArray<Type>())

        private val WRITE_KEYWORDS = listOf("set", "put", "swap", "exchange")

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

        private fun GeneratorAdapter.copyParameters(methodDescriptor: String): IntArray = copyParameters(Type.getArgumentTypes(methodDescriptor))

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
            if (ownerInternal.startsWith(TRANSFORMED_PACKAGE_INTERNAL_NAME)) {
                ownerInternal = ownerInternal.substring(TRANSFORMED_PACKAGE_INTERNAL_NAME.length)
            }
            return try {
                val clazz = Class.forName(ownerInternal.toClassName())
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
            if (internalName.startsWith(TRANSFORMED_PACKAGE_INTERNAL_NAME)) {
                internalName = internalName.substring(TRANSFORMED_PACKAGE_INTERNAL_NAME.length)
            }
            return try {
                val clazz = Class.forName(internalName.toClassName())
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

        private fun String.isNotPrimitiveType() = startsWith("L") || startsWith("[")

        private fun isSuspend(owner: String, methodName: String, descriptor: String): Boolean =
            try {
                Class.forName(owner.toClassName()).kotlin.declaredFunctions.any {
                    it.isSuspend && it.name == methodName && Method.getMethod(it.javaMethod).descriptor == descriptor
                }
            } catch(e: Throwable) {
                // kotlin reflection is not available for some functions
                false
            }

        private fun String.toClassName() = this.replace('/', '.')

        private fun isSuspendStateMachine(internalClassName: String): Boolean {
            // all named suspend functions extend kotlin.coroutines.jvm.internal.ContinuationImpl
            // it is internal, so check by name
            return Class.forName(internalClassName.toClassName()).superclass?.name == "kotlin.coroutines.jvm.internal.ContinuationImpl"
        }

        private fun isStrategyCall(owner: String) = owner.startsWith("org/jetbrains/kotlinx/lincheck/strategy")

        private fun isAFU(owner: String) = owner.startsWith("java/util/concurrent/atomic/Atomic") && owner.endsWith("FieldUpdater")

        // returns true only the method is declared in this class and is not inherited
        private fun isClassMethod(owner: String, methodName: String, desc: String): Boolean =
            Class.forName(owner.toClassName()).declaredMethods.any {
                val method = Method.getMethod(it)
                method.name == methodName && method.descriptor == desc
            }

        private fun isAFUMethodCall(opcode: Int, owner: String, methodName: String, desc: String) =
            opcode == INVOKEVIRTUAL && isAFU(owner) && isClassMethod(owner, methodName, desc)
    }
}

/**
 * This class is used for getting the [sun.misc.Unsafe] instance.
 * We need it in some transformed classes from the `java.util.` package,
 * and it cannot be accessed directly after the transformation.
 */
internal object UnsafeHolder {
    @Volatile
    private var theUnsafe: Any? = null

    @JvmStatic
    fun getUnsafe(): Any {
        if (theUnsafe == null) {
            try {
                val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
                f.isAccessible = true
                theUnsafe = f.get(null)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
        return theUnsafe!!
    }
}