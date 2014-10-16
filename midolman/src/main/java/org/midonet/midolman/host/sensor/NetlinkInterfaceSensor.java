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
package org.midonet.midolman.host.sensor;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
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
    public void updateInterfaceData(Set<InterfaceDescription> interfaces) {
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
