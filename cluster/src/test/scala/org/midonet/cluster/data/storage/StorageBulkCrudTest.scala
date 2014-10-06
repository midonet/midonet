/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.lang.Long
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer

import com.google.common.collect.Multimap
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.StorageEval.BulkUpdateEval
import org.midonet.cluster.data.storage.StorageEval.BulkUpdateEvalOrBuilder
import org.midonet.cluster.data.storage.StorageEval.EvalResult
import org.midonet.cluster.data.storage.StorageEval.EvalResult.TestItem
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Devices.Bridge
import org.midonet.cluster.models.Devices.Port
import org.midonet.cluster.models.Devices.Rule
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Defines tests for bulk CRUD operations with Storage Service. To actually run
 * the tests, one must extend this trait and set up appropriate storage backend.
 */
trait StorageBulkCrudTest extends FlatSpec
                          with StorageServiceTester
                          with BeforeAndAfterAll {
    val log = LoggerFactory.getLogger(classOf[StorageBulkCrudTest])
    val TRIAL_SIZE = 1000
    val testResults = ArrayBuffer[BulkUpdateEval]()

    def experimentCommonSettings =
        BulkUpdateEval.newBuilder()
                      .setType(BulkUpdateEval.Type.LOCAL_ZK_ZOOM)
                      .setNumThreads(1)  // Default thread size
                      .setMultiSize(1)   // Default multi operation size
                      .setTrialSize(TRIAL_SIZE)

    def getExperimentDate = new Date().toString

    def getThreadPool(numThreads: Int) =
        Executors.newFixedThreadPool(numThreads)

    def getResultsBuilder(test: BulkUpdateEval.Builder) =
        test.addResultBuilder().setTimestamp(getExperimentDate)

    def collectTest(test: BulkUpdateEval.Builder) {
        testResults += test.build()
    }

    override protected def afterAll() {
        super.afterAll()
        // Outputs the test results to log.
        for (result <- testResults.toArray) {
            log.info("\n{}", result)
        }
    }

    /**
     * Creates a runnable that gets a collection of devices with given type and
     * ID.
     *
     * @param devices A collection of device type / ID pairs.
     * @param counter A counter that is incremented when a read is successful.
     */
    def deviceCollGetRunnable(devices: Multimap[Class[_], Commons.UUID],
                              counter: AtomicLong) = {
        new Runnable {
            override def run() {
                for (clazzEntry <- devices.entries) {
                    try {
                        get(clazzEntry.getKey, clazzEntry.getValue)
                        counter.incrementAndGet()
                    } catch {
                        case e: Exception =>
                            log.warn("Error reading a device {} with ID {}",
                                     clazzEntry.getKey, clazzEntry.getValue)
                    }
                }
            }
        }
    }

    /**
     * Measures the latency of reading in a single item for the specified number
     * of times with the specified number of threads, and returns a total
     * duration in millisecond. The total number of reads is THREAD_SIZE x
     * TRIAL_SIZE. The goal is to measure simple read latency and to evaluate if
     * the read linearly scales with the number of threads.
     *
     * NOTE: ZK may likely cache the node, thus the test may not be measuring
     * the true latency. We need to test random read.
     *
     * @return A total duration of THREAD_SIZE x TRIAL_SIZE reads with THREAD
     * SIZE threads in millisecond.
     */
    def readLatency(test: BulkUpdateEvalOrBuilder) = {
        val bridge = createBridge("read latency test bridge")
        val latency = taskExecLatency(test.getNumThreads,
                                      test.getTrialSize,
                                      () => get(classOf[Bridge], bridge.getId))
        delete(classOf[Bridge], bridge.getId)
        latency
    }

    /**
     * Measures the latency of writing and deleting a single item for the
     * specified number of times, and returns a total duration in millisecond.
     * The total number of writes is 2 x THREAD_SIZE x TRIAL_SIZE. The goal is
     * to measure simple write latency and to evaluate if the write linearly
     * scales with the number of threads.
     *
     * @return A total duration of 2 x THREAD_SIZE x TRIAL_SIZE writes with
     * THREAD SIZE threads in millisecond.
     */
    def writeLatency(test: BulkUpdateEvalOrBuilder): Long = {
        val bridge = Bridge.newBuilder
                           .setId(randomUuidProto)
                           .setName("write latency test bridge")
                           .build()
        taskExecLatency(test.getNumThreads,
                        test.getTrialSize,
                        {() => create(bridge)
                               delete(bridge.getClass, bridge.getId)})
    }

    /**
     * Executes a given task for the specified number of trials per thread
     * concurrently with a given number of threads. In total it executes
     * numThreads times numTrial tasks. Returns a total time in milliseconds it
     * took for all threads to complete execution.
     */
    def taskExecLatency(
            numThreads: Int, numTrial: Int, task: () => Unit) = {
        val pool = getThreadPool(numThreads)
        val current = System.currentTimeMillis()
        for (_ <- 1 to numThreads) pool.execute(new Runnable() {
            override def run() {
                for (i <- 1 to numTrial) task()
            }
        })

        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS)
        val end = System.currentTimeMillis()
        end - current
    }

    /**
     * Builds a single-tenant test layout according to the topology
     * specifications, where there's a single provider router, under which
     * exists a single tenant router, which in turn has a specified number of
     * bridges and a specified number of ports per each bridge. As it builds the
     * specified topology, it records how much time it took to build it in the
     * given test results builder.
     *
     * @param test Test specifications.
     * @param result Test results builder.
     */
    def buildLayoutAndMeasureLatency(test: BulkUpdateEvalOrBuilder,
                                     result: EvalResult.Builder) {
        val testItemName = "Whole topology read/write latency"
        val testItem = result.addTestItemBuilder()
        testItem.setItemName(testItemName)
        try {
            val topology = test.getTopology
            val bridgesPerTenant = topology.getBridgesPerTenant
            val portsPerBridge = topology.getPortsPerBridge
            val numRules = topology.getNumRulesPerBridgeChains
            val devices = emptyDeviceCollection

            val start = System.currentTimeMillis()
            val providerRouter = createRouter("provider router", devices)
            val tenantRouter = createRouter("tenant router", devices)
            connect(providerRouter, tenantRouter)

            val pool = getThreadPool(test.getNumThreads)
            val successfulTasks = new AtomicLong()
            for (bridge_i <- 1 to bridgesPerTenant) {
                val bridge = createBridge(s"bridge$bridge_i", devices)
                connect(tenantRouter, bridge)

                // Dispatch port / chain creations to separate threads.
                pool.execute(addPortsAndChainsRunnable(
                        bridge, portsPerBridge, numRules,
                        test.getMultiSize, devices, successfulTasks))
            }

            pool.shutdown()
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS)
            val end = System.currentTimeMillis()
            assert(successfulTasks.get === bridgesPerTenant,
                   s"Executed $bridgesPerTenant writes, but succeeded only " +
                   s"$successfulTasks writes.")

            val layoutWrite = end - start
            // Test reading all the devices in the layout.
            val layoutRead = testReadDevices(test.getNumThreads, devices)

            // Record the results.
            testItem.addDataBuilder().setProperty("topology size")
                                     .setValue(devices.size.toString)
            testItem.addDataBuilder().setProperty("read")
                                     .setValue(layoutRead.toString)
            testItem.addDataBuilder().setProperty("write")
                                     .setValue(layoutWrite.toString)
            testItem.setTestStatus(TestItem.TestStatus.SUCCESS)
        } catch {
            case ex: Exception =>
                log.warn(s"$testItemName failed with exception", ex)
                testItem.setTestStatus(TestItem.TestStatus.FAILURE)
                val dataBuilder = testItem.addDataBuilder()
                dataBuilder.setProperty("exception")
                if (ex.getMessage != null) dataBuilder.setValue(ex.getMessage)
                else dataBuilder.setValue(ex.getStackTraceString)
        }
    }

    /* Adds ports and chains to a given bridge either with either normal CRUD
     * operations or with "multi" operations of the specified operation size.
     *
     * @param bridge A bridge to which ports and chains are attached. The bridge
     * must already exist.
     * @param numPorts A number of ports to be attached to the bridge.
     * @param numRules A number of rules that inbound and outbound chains of the
     * bridge have.
     * @param devices A collection of devices created by this call.
     * @param taskSuccessCounter A successful task counter to be incremented
     * when the whole operation operations are successful.
     */
    private def addPortsAndChainsRunnable(bridge: Bridge,
                                          numPorts: Int,
                                          numRules: Int,
                                          multiSize: Int,
                                          devices: Devices,
                                          taskSuccessCounter: AtomicLong) = {
        new Runnable() {
            override def run() {
                try {
                    val multis =
                        if (multiSize > 1) ListBuffer[PersistenceOp]() else null
                    for (_ <- 1 to numPorts)
                        attachPortTo(bridge, multis, devices)

                    val chains = (1 to 2).map(_ => {
                        val chain = createChain(multis, devices)
                        for (_ <- 1 to numRules)
                            addRule(chain, Rule.Action.ACCEPT, multis, devices)
                        chain
                    })
                    attachChains(bridge, chains(0), chains(1), multis)

                    if (multis != null)  // Performs multi operations
                        multis.grouped(multiSize).foreach(multi)

                    taskSuccessCounter.incrementAndGet()
                } catch {
                    case e: Exception =>
                        log.warn("Error constructing a bridge.", e)
                }
            }
        }
    }
    /**
     * Measure the read latency of reading the specified devices with the
     * number of threads specified in the test spec.
     */
    def testReadDevices(numThreads: Int, devices: Devices) = {
        // Split the reads into NUM-THREADS batches.
        val workQueues = splitDeviceCollection(devices, numThreads)
        val pool = getThreadPool(numThreads)
        val successfulTask = new AtomicLong()
        val current = System.currentTimeMillis()
        for (work <- workQueues)
            pool.execute(deviceCollGetRunnable(work, successfulTask))

        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        assert(successfulTask.get() === devices.size(),
               s"Executed ${devices.size} reads, but succeeded only " +
               s"$successfulTask reads.")

        val end = System.currentTimeMillis()
        end - current
    }

    /**
     * Evaluates multiple reads and writes latency.
     *
     * @param test Test specifications.
     * @param result Test results builder.
     * @param testItemName The name of this test item.
     */
    def testSimpleBulkUpdate(test: BulkUpdateEvalOrBuilder,
                             result: EvalResult.Builder,
                             testItemName: String) {
        val testItem = result.addTestItemBuilder()
        testItem.setItemName(testItemName)
        try {
            val read = readLatency(test)
            testItem.addDataBuilder().setProperty("read")
                                     .setLatencyMilliSec(read)

            val write = writeLatency(test)
            testItem.addDataBuilder().setProperty("write")
                                     .setLatencyMilliSec(write)
            testItem.setTestStatus(TestItem.TestStatus.SUCCESS)
        } catch {
            case ex: Exception =>
                log.warn(s"$testItemName failed with exception", ex)
                testItem.setTestStatus(TestItem.TestStatus.FAILURE)
                val dataBuilder = testItem.addDataBuilder()
                dataBuilder.setProperty("exception")
                if (ex.getMessage != null) dataBuilder.setValue(ex.getMessage)
                else dataBuilder.setValue(ex.getStackTraceString)
        }
    }

    /**
     * Tests whether the storage service can update a plain bridge without any
     * ports or chains with 10k port IDs (and in/out-bound filter IDs) in a
     * single update request.
     */
    def build10KPortsBridgeOneShot(test: BulkUpdateEval.Builder,
                                   result: EvalResult.Builder) {
        val testItemName = "One-shot 10K ports-bridge creation"
        val multiSize = 1000
        val topology = test.getTopologyBuilder
        topology.setBridgesPerTenant(1)
        topology.setPortsPerBridge(10000)
        topology.setNumRulesPerBridgeChains(10)

        val testItem = result.addTestItemBuilder()
        testItem.setItemName(testItemName)
        try {
            val devices = emptyDeviceCollection
            val bridge = createBridge("bridge1", devices)
            val chains = (1 to 2).map(_ => {
                val chain = createChain(null, devices)
                for (_ <- 1 to topology.getNumRulesPerBridgeChains)
                    addRule(chain, Rule.Action.ACCEPT, null, devices)
                chain
            })
            val multis = ListBuffer[PersistenceOp]()
            for (_ <- 1 to topology.getPortsPerBridge)
                createPort(multis, devices)
            multis.grouped(multiSize).foreach(multi)

            // Created all the devices.
            testItem.addDataBuilder().setProperty("topology size")
                                     .setValue(devices.size.toString)

            // Update a bridge with 10K port IDs.
            val bridgeWithPorts = bridge.toBuilder
            for (portId <- devices.get(classOf[Port])) {
                bridgeWithPorts.addPortIds(portId)
            }
            bridgeWithPorts.setInboundFilterId(chains(0).getId)
            bridgeWithPorts.setInboundFilterId(chains(1).getId)
            update(bridgeWithPorts.build())

            // Record the results.
            testItem.setTestStatus(TestItem.TestStatus.SUCCESS)
        } catch {
            case ex: Exception =>
                log.warn(s"$testItemName failed with exception", ex)
                testItem.setTestStatus(TestItem.TestStatus.FAILURE)
                val failureData = testItem.addDataBuilder()
                failureData.setProperty("exception")
                if (ex.getMessage != null) failureData.setValue(ex.getMessage)
                else failureData.setValue(ex.getStackTraceString)
        }
    }


    "Empty layout" should "be tested for read/write latency" ignore {
        val test = experimentCommonSettings
        val result = getResultsBuilder(test)

        testSimpleBulkUpdate(test, result, "simple read/write on empty data")
        collectTest(test)
    }

    "Base layout" should "be handled efficiently" ignore {
        val testBase = experimentCommonSettings
        testBase.getTopologyBuilder.setNumTenants(1)
                                   .setBridgesPerTenant(2)
                                   .setPortsPerBridge(4)
                                   .setNumRulesPerBridgeChains(2)
        for (numThreads <- Array(1, 2, 4)) {
            if (numThreads != 1) cleanUpDeviceData()
            val test = testBase.clone().setNumThreads(numThreads)
            val result = getResultsBuilder(test)

            buildLayoutAndMeasureLatency(test, result)
            testSimpleBulkUpdate(test, result,
                                 "simple read/write after building topology")
            collectTest(test)
        }
    }

    "More threads constructing bridges" should "decrease write latency" ignore {
        val testBase = experimentCommonSettings
        testBase.getTopologyBuilder.setBridgesPerTenant(100)
                                   .setPortsPerBridge(10)
                                   .setNumRulesPerBridgeChains(2)
        for (numThreads <- Array(1, 2, 4)) {
            if (numThreads != 1) cleanUpDeviceData()
            val test = testBase.clone().setNumThreads(numThreads)
            val result = getResultsBuilder(test)

            buildLayoutAndMeasureLatency(test, result)
            collectTest(test)
        }
    }

    "Multi-operation" can "bulk-create ports & chains" ignore {
        val testBase = experimentCommonSettings
        testBase.getTopologyBuilder.setBridgesPerTenant(1)
                                   .setPortsPerBridge(1000)
                                   .setNumRulesPerBridgeChains(1000)
        for (multiSize <- Array(100, 1000)) {
            if (multiSize != 100) cleanUpDeviceData()
            val test = testBase.clone().setMultiSize(multiSize)
            val result = getResultsBuilder(test)

            buildLayoutAndMeasureLatency(test, result)
            collectTest(test)
        }
    }

    "Max-ports-per-bridge" can "be handled efficiently" ignore {
        val testBase = experimentCommonSettings
        testBase.getTopologyBuilder.setBridgesPerTenant(1)
                                   .setPortsPerBridge(10000)
                                   .setNumRulesPerBridgeChains(1000)
        for (multiSize <- Array(1000, 5000, 7000)) {
            if (multiSize != 5000) cleanUpDeviceData()
            val test = testBase.clone().setMultiSize(multiSize)
            val result = getResultsBuilder(test)

            buildLayoutAndMeasureLatency(test, result)
            collectTest(test)
        }
    }

    it can "be created in a single update request" ignore {
        val test = experimentCommonSettings
        build10KPortsBridgeOneShot(test, getResultsBuilder(test))
        collectTest(test)
    }

    "Max-total-ports" should "be handled efficiently" ignore {
        val test = experimentCommonSettings
        test.getTopologyBuilder.setBridgesPerTenant(100)
                               .setPortsPerBridge(10000)
                               .setNumRulesPerBridgeChains(1000)
        val result = getResultsBuilder(test)

        buildLayoutAndMeasureLatency(test, result)
        testSimpleBulkUpdate(test, result,
                             "simple read/write after building topology")
        collectTest(test)
    }

    "Conflicting port reqs" should "succeed only for one req" ignore {
        val port = createPort()
        val peers = Collections.synchronizedList(new ArrayList[Port]())

        val threading = 8
        val pool = getThreadPool(threading)
        for (i <- 1 to threading) {
            pool.execute(new Runnable {
                override def run() {
                    // Create a peer, triggering back-ref update on main port.
                    val peerPort = Port.newBuilder
                                       .setId(randomUuidProto)
                                       .setPeerUuid(port.getId)
                                       .build()
                    create(peerPort)
                    peers.add(peerPort)
                }
            })
        }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS)

        assert(peers.size() === 1, "Failed to handle conflicting writes.")

        val portInZk = get(classOf[Port], port.getId)
        val peerInZk = get(classOf[Port], peers(0).getId)
        assert(portInZk.getPeerUuid === peers(0).getId,
               "The port in ZK has a corrupted peer port ID.")
        assert(peerInZk.getPeerUuid === port.getId,
               "The peer in ZK has a corrupted peer port ID.")
    }
}
