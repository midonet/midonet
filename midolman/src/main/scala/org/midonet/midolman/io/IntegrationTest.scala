/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.io

import java.util.concurrent.atomic.AtomicInteger

import org.midonet.config.ConfigProvider
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.odp.OvsConnectionOps
import org.midonet.odp.test.OvsIntegrationTestBase

object ConnectionFactory {

    val conf = ConfigProvider.defaultConfig(classOf[MidolmanConfig])

    def fromManager(getter: () => ManagedDatapathConnection) = {
        val manager = getter()
        manager.start()
        manager.getConnection()
    }

    def blockingConnection() = {
        fromManager { () =>
            new BlockingTransactorDatapathConnection("blocking", conf)
        }
    }

    def selectorBasedConnection(singleThreaded: Boolean = true) = {
        fromManager { () =>
            new SelectorBasedDatapathConnection("selector", conf,
                                                singleThreaded, null)
        }
    }

    def fromOneToOnePool(nConns: Int = 1) = {
        val pool = new OneToOneConnectionPool("pool", nConns, conf)
        pool.start()
        pool
    }
}

object BlockingTransactorTest extends OvsIntegrationTestBase {
    val baseConnection = ConnectionFactory.blockingConnection()
}

object OneSelectorConnectionTest extends OvsIntegrationTestBase {
    val baseConnection = ConnectionFactory.selectorBasedConnection()
}

object TwoSelectorsConnectionTest extends OvsIntegrationTestBase {
    val baseConnection = ConnectionFactory.selectorBasedConnection(false)
}

object PoolOfOneConnectionTest extends OvsIntegrationTestBase {
    val baseConnection = ConnectionFactory.fromOneToOnePool().get(0)
}

object PoolOfTenConnectionTest extends OvsIntegrationTestBase {
    val nCons = 10
    val pool = ConnectionFactory.fromOneToOnePool(nCons)
    val baseConnection = pool.get(0)
    val cons = Vector.tabulate(nCons) { i => new OvsConnectionOps(pool.get(i)) }
    val counter = new AtomicInteger(0)
    override def con = cons(counter.getAndIncrement % nCons)
}
