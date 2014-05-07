/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.util.List;
import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadGatewayHttpException;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.GatewayTimeoutHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepDataClientProvider;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.opendaylight.controller.sal.utils.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.midonet.api.validation.MessageProperty.VTEP_INACCESSIBLE;
import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_PORT_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

abstract public class AbstractVtepResource extends AbstractResource {

    private static final Logger log = LoggerFactory.getLogger(
        AbstractVtepResource.class);

    private final VtepDataClientProvider vtepClientProvider;
    protected final ResourceFactory factory;

    @Inject
    public AbstractVtepResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, Validator validator,
                               DataClient dataClient, ResourceFactory factory,
                               VtepDataClientProvider vtepClientProvider) {
        super(config, uriInfo, context, dataClient, validator);
        this.vtepClientProvider = vtepClientProvider;
        this.factory = factory;
    }

    /**
     * Creates a VTEP client and opens a connection to the VTEP at
     * mgmtIp:mgmtPort.
     *
     * @param throwOnFailure If true, throws a GatewayTimeoutHttpException
     *                       when failing to connect to VTEP.
     */
    protected final VtepDataClient getVtepClient(IPv4Addr mgmtIp, int mgmtPort,
                                                 boolean throwOnFailure) {
        VtepDataClient vtepClient = vtepClientProvider.makeClient();
        try {
            vtepClient.connect(mgmtIp, mgmtPort);
            return vtepClient;
        } catch (IllegalStateException ex) {
            log.warn("Unable to connect to VTEP: ", ex);
            if (!throwOnFailure)
                return null;

            throw new GatewayTimeoutHttpException(getMessage(
                VTEP_INACCESSIBLE, mgmtIp, mgmtPort));
        }
    }


    protected final org.midonet.cluster.data.VTEP getVtepOrThrow(
        String ipAddrStr, boolean badRequest)
        throws StateAccessException, SerializationException
    {
        return getVtepOrThrow(parseIPv4Addr(ipAddrStr), badRequest);
    }

    /**
     * Gets the VTEP record with the specified IP address. If not found,
     * will throw a BadRequestHttpException if badRequest is true, or a
     * NotFoundHttpException otherwise.
     */
    protected final org.midonet.cluster.data.VTEP getVtepOrThrow(
        IPv4Addr ipAddr, boolean badRequest)
        throws StateAccessException, SerializationException {
        org.midonet.cluster.data.VTEP dataVtep = dataClient.vtepGet(ipAddr);
        if (dataVtep == null) {
            String msg = getMessage(VTEP_NOT_FOUND, ipAddr);
            throw badRequest ? new BadRequestHttpException(msg) :
                new NotFoundHttpException(msg);
        }
        return dataVtep;
    }

    /**
     * Gets a list of PhysicalPorts belonging to the specified VTEP using the
     * provided VtepDataClient.
     */
    protected final List<PhysicalPort> getPhysicalPorts(
        VtepDataClient vtepClient, org.midonet.cluster.data.VTEP vtep)
        throws StateAccessException
    {
        // Get the physical switch.
        PhysicalSwitch ps = getPhysicalSwitch(vtepClient, vtep.getId());
        if (ps == null) {
            throw new GatewayTimeoutHttpException(getMessage(
                VTEP_INACCESSIBLE, vtep.getId(), vtep.getMgmtPort()));
        }

        // TODO: Handle error if this fails or returns null.
        return vtepClient.listPhysicalPorts(ps.uuid);
    }

    /**
     * Gets the PhysicalPort named portName from the specified VTEP
     * using the provided VtepDataClient.
     */
    protected final PhysicalPort getPhysicalPort(VtepDataClient vtepClient,
                         org.midonet.cluster.data.VTEP vtep, String portName)
        throws StateAccessException, SerializationException
    {
        // Find the requested port.
        List<PhysicalPort> pports = getPhysicalPorts(vtepClient, vtep);
        for (PhysicalPort pport : pports)
            if (pport.name.equals(portName))
                return pport;

        // Switch doesn't have the specified port.
        throw new NotFoundHttpException(getMessage(
            VTEP_PORT_NOT_FOUND, vtep.getId(), vtep.getMgmtPort(),
            portName));
    }

    /**
     * Gets the PhysicalSwitch record from the database of the VTEP at
     * the specified IP and port.
     *
     * @param throwOnFailure If true, throws a GatewayTimeoutHttpException
     *                       when failing to connect to VTEP.
     */
    protected final PhysicalSwitch getPhysicalSwitch(IPv4Addr mgmtIp,
                                                     int mgmtPort,
                                                     boolean throwOnFailure)
    {
        VtepDataClient client = getVtepClient(mgmtIp, mgmtPort, throwOnFailure);
        if (client == null)
            return null;

        try {
            return getPhysicalSwitch(client, mgmtIp);
        } finally {
            client.disconnect();
        }
    }

    /**
     * Gets the PhysicalSwitch record with the specified managementIp
     * address using the provided VtepDataClient.
     */
    protected final PhysicalSwitch getPhysicalSwitch(VtepDataClient vtepClient,
                                                     IPv4Addr mgmtIp)
    {

        List<PhysicalSwitch> switches = vtepClient.listPhysicalSwitches();
        if (switches.size() == 1)
            return switches.get(0);

        for (PhysicalSwitch ps : switches)
            if (ps.mgmtIps != null && ps.mgmtIps.contains(mgmtIp.toString()))
                return ps;

        return null;
    }

    protected final void throwIfFailed(Status status) {

        if (status.isSuccess())
            return;

        switch(status.getCode()) {
            case BADREQUEST:
                throw new BadRequestHttpException(status.getDescription());
            case CONFLICT:
                throw new ConflictHttpException(status.getDescription());
            case NOTFOUND:
                throw new NotFoundHttpException(status.getDescription());
            default:
        }

        log.warn("Unexpected response from VTEP: " + status);
        throw new BadGatewayHttpException(status.getDescription());
    }

}
