/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import com.google.inject.{Key, Injector}
import org.midonet.cluster.{Client, DataClient}
import java.util.UUID
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.odp.protos.{OvsDatapathConnection, MockOvsDatapathConnection}
import org.midonet.midolman.io.ManagedDatapathConnection
import org.midonet.midolman.guice.datapath.DatapathModule.UPCALL_DATAPATH_CONNECTION

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

    def dpConn(): OvsDatapathConnection = {
        val key = Key.get(classOf[ManagedDatapathConnection],
                          classOf[UPCALL_DATAPATH_CONNECTION])
        injector.getInstance(key).getConnection
    }
}
