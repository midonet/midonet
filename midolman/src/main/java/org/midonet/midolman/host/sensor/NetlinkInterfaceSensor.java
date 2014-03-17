/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.host.sensor;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.guice.datapath.DatapathModule;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.odp.DpPort;


/**
 * Interface sensor using Netlink.
 * Currently implemented using polling. Should rather be done on listening
 * to NETLINK_ROUTE for interface change. See #596 on GH. Also, this class
 * has only two methods, one of which is new. Could rather be a function.
 */
public class NetlinkInterfaceSensor implements InterfaceSensor {

    private static final Logger log = LoggerFactory.getLogger(
            NetlinkInterfaceSensor.class);

    @Inject
    private DatapathConnectionPool datapathConnPool;

    // Timeout value in milliseconds when accessing the datapath
    private static final long NETLINK_CONN_TIMEOUT =
            TimeUnit.SECONDS.toMillis(3);

    @Override
    public List<InterfaceDescription> updateInterfaceData
            (List<InterfaceDescription> interfaces) {

        try {

            for (InterfaceDescription desc : interfaces) {

                try {

                    DpPort port = getDatapathPort( desc.getName() );

                    /* set port type, endpoint to DATAPAH, type to VIRT */
                    desc.setPortType(port.getType());
                    desc.setEndpoint(InterfaceDescription.Endpoint.DATAPATH);
                    desc.setType(InterfaceDescription.Type.VIRT);

                } catch (ExecutionException ex) {
                    /* interface is not a datapath port => try next one */
                    log.trace(
                        "Port not in datapath for interface " +
                        desc.getName() + " => " + ex.getCause()
                    );
                } catch (TimeoutException ex) {
                    /* ValueFuture#get() timeout because ??? => try next one */
                    log.warn(
                        "Timeout exception thrown with value: " +
                        NETLINK_CONN_TIMEOUT + " ms"
                    );
                }

            }

        } catch (InterruptedException ex) {
            log.debug("Got interrupted => interrupting current thread");
            Thread.currentThread().interrupt();
        }

        return interfaces;
    }

    /**
     * Helper function for NetlinkInterfaceSensor#updateInterfaceData.
     * Query the OvsDatapathConnection instance for a port.
     * The query returns an async Future which is immediately asked for
     * its value. Possible improvement: have the datapath knows about its
     * ports and have a O(1) hasPort method to skip useless polling on
     * unknown ports. It would also allow disambiguation with real Execution
     * Exceptions.
     * @param portName name of the port to find
     * @return the Port object used by updateInterfaceData to update the
     * state of the list of InterfaceDescription
     * @see NetlinkInterfaceSensor#updateInterfaceData
     */
    protected DpPort getDatapathPort(String portName)
        throws ExecutionException, TimeoutException, InterruptedException {
            return datapathConnPool.get(portName.hashCode()).futures
                .portsGet(portName, null)  /* return Future<Port> */
                .get(NETLINK_CONN_TIMEOUT, TimeUnit.MILLISECONDS);
    }
}
