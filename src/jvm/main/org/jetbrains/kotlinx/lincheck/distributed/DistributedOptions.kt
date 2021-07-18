package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import java.lang.IllegalArgumentException
import java.util.*

enum class MessageOrder {
    FIFO,
    ASYNCHRONOUS
}


enum class CrashMode {
    NO_CRASHES,
    NO_RECOVERIES,
    ALL_NODES_RECOVER,
    MIXED
}

enum class NetworkPartitionMode {
    NONE,
    HALVES,
    SINGLE
}

enum class TestingMode {
    STRESS,
    MODEL_CHECKING
}


class NodeTypeInfo(val minNumberOfInstances: Int, val maxNumberOfInstances: Int, val canFail: Boolean) {
    fun minimize() = NodeTypeInfo(minNumberOfInstances, maxNumberOfInstances - 1, canFail)
}

class DistributedOptions<Message, Log> : Options<DistributedOptions<Message, Log>,
        DistributedCTestConfiguration<Message, Log>>() {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5000
    }

    private var isNetworkReliable: Boolean = true
    private var messageOrder: MessageOrder = MessageOrder.FIFO
    private var maxNumberOfFailedNodes: (Int) -> Int = { 0 }
    private var crashMode: CrashMode = CrashMode.NO_RECOVERIES
    private var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    private var messageDuplication: Boolean = false
    private var networkPartitions: NetworkPartitionMode = NetworkPartitionMode.NONE
    private var testClasses = HashMap<Class<out Node<Message>>, NodeTypeInfo>()
    private var logFileName: String? = null
    private var testingMode: TestingMode = TestingMode.STRESS
    private val maxNumberOfFailedNodesForType = mutableMapOf<Class<out Node<Message>>, (Int) -> Int>()

    init {
        timeoutMs = DEFAULT_TIMEOUT_MS
    }

    fun networkReliable(isReliable: Boolean): DistributedOptions<Message, Log> {
        this.isNetworkReliable = isReliable
        return this
    }

    fun nodeType(
        cls: Class<out Node<Message>>,
        numberOfInstances: Int,
        canFail: Boolean = true
    ): DistributedOptions<Message, Log> {
        this.testClasses[cls] = NodeTypeInfo(numberOfInstances, numberOfInstances, canFail)
        return this
    }

    fun nodeType(
        cls: Class<out Node<Message>>,
        minNumberOfInstances: Int,
        maxNumberOfInstances: Int,
        canFail: Boolean = true
    ): DistributedOptions<Message, Log> {
        this.testClasses[cls] = NodeTypeInfo(minNumberOfInstances, maxNumberOfInstances, canFail)
        return this
    }

    fun messageOrder(messageOrder: MessageOrder): DistributedOptions<Message, Log> {
        this.messageOrder = messageOrder
        return this
    }

    fun setMaxNumberOfFailedNodes(maxNumOfFailedNodes: (Int) -> Int): DistributedOptions<Message, Log> {
        this.maxNumberOfFailedNodes = maxNumOfFailedNodes
        return this
    }

    fun setMaxNumberOfFailedNodes(cls: Class<out Node<Message>>, maxNumOfFailedNodes: (Int) -> Int): DistributedOptions<Message, Log> {
        this.maxNumberOfFailedNodesForType[cls] = maxNumOfFailedNodes
        return this
    }

    fun crashMode(crashMode: CrashMode): DistributedOptions<Message, Log> {
        this.crashMode = crashMode
        return this
    }

    fun invocationsPerIteration(invocations: Int): DistributedOptions<Message, Log> {
        this.invocationsPerIteration = invocations
        return this
    }

    fun messageDuplications(duplications: Boolean): DistributedOptions<Message, Log> {
        this.messageDuplication = duplications
        return this
    }

    fun networkPartitions(partitionMode: NetworkPartitionMode): DistributedOptions<Message, Log> {
        this.networkPartitions = partitionMode
        return this
    }

    fun storeLogsForFailedScenario(fileName: String): DistributedOptions<Message, Log> {
        logFileName = fileName
        return this
    }

    fun setTestMode(mode: TestingMode): DistributedOptions<Message, Log> {
        testingMode = mode
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>): DistributedCTestConfiguration<Message, Log> {
        return DistributedCTestConfiguration(
            testClass, iterations, threads,
            actorsPerThread, executionGenerator,
            verifier, invocationsPerIteration, isNetworkReliable,
            messageOrder, maxNumberOfFailedNodes, maxNumberOfFailedNodesForType, crashMode,
            messageDuplication, networkPartitions, testClasses, logFileName, testingMode,
            requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
            chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, customScenarios
        )
    }

    fun check() {
        val testClass = testClasses.keys.find {
            it.methods.any { m ->
                m.isAnnotationPresent(Operation::class.java)
            }
        }
            ?: throw IllegalArgumentException("No operations to check")
        threads = testClasses[testClass]!!.maxNumberOfInstances
        LinChecker.check(testClass, options = this)
    }
}