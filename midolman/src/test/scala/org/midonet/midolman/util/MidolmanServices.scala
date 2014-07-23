/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.util

import org.midonet.cluster.data.neutron.LoadBalancerApi

import scala.concurrent.ExecutionContext
import akka.actor.ActorSystem
import com.google.inject.Injector
import org.midonet.cluster.{Client, DataClient}
import org.midonet.midolman.io.{UpcallDatapathConnectionManager}
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager
import org.midonet.odp.protos.{OvsDatapathConnection, MockOvsDatapathConnection}
import org.midonet.cluster.Client
import org.midonet.cluster.DataClient
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.services.HostIdProviderService


trait MidolmanServices {
    var injector: Injector

    def clusterClient() =
        injector.getInstance(classOf[Client])

    def clusterDataClient() =
        injector.getInstance(classOf[DataClient])

    def hostId() =
        injector.getInstance(classOf[HostIdProviderService]).getHostId

    def mockDpConn()(implicit ec: ExecutionContext, as: ActorSystem) = {
        dpConn().asInstanceOf[MockOvsDatapathConnection]
    }

    def dpConn()(implicit ec: ExecutionContext, as: ActorSystem):
        OvsDatapathConnection = {
        val mockConnManager =
            injector.getInstance(classOf[UpcallDatapathConnectionManager]).
                asInstanceOf[MockUpcallDatapathConnectionManager]
        mockConnManager.initialize()
        mockConnManager.conn.getConnection
    }
}
