/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.io

import java.util.concurrent.atomic.AtomicInteger

import com.codahale.metrics.MetricRegistry

import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.odp.OvsConnectionOps
import org.midonet.odp.test.OvsIntegrationTestBase
import org.midonet.util.Bucket;

object ConnectionFactory {

    val conf = new MidolmanConfig(MidoTestConfigurator.forAgents)

    def fromManager(getter: () => ManagedDatapathConnection) = {
        val manager = getter()
        manager.start()
        manager.getConnection()
    }

    def selectorBasedConnection(singleThreaded: Boolean = true) = {
        fromManager { () =>
            new SelectorBasedDatapathConnection("selector", conf, singleThreaded,
                                                Bucket.BOTTOMLESS,
                                                new MetricRegistry())
        }
    }


    def fromOneToOnePool(nConns: Int = 1) = {
        val pool = new OneToOneConnectionPool("pool", nConns, conf,
                                              new MetricRegistry())
        pool.start()
        pool
    }
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
