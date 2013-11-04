/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import com.google.inject.Injector
import org.midonet.cluster.{Client, DataClient}
import java.util.UUID
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.odp.protos.{OvsDatapathConnection, MockOvsDatapathConnection}

trait MidolmanServices {
    var injector: Injector

    def clusterClient() =
        injector.getInstance(classOf[Client])

    def clusterDataClient() =
        injector.getInstance(classOf[DataClient])

    def hostId() =
        injector.getInstance(classOf[HostIdProviderService]).getHostId

    def mockDpConn() =
        dpConn().asInstanceOf[MockOvsDatapathConnection]

    def dpConn(): OvsDatapathConnection =
        injector.getInstance(classOf[OvsDatapathConnection])
}
