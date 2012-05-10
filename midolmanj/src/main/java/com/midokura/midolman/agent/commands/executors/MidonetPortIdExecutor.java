/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.agent.commands.executors;

import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;

/**
 * Command executor that handles adding/removing an interface to/from a port.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/8/12
 */
public class MidonetPortIdExecutor extends AbstractCommandExecutor<UUID> {

    @Inject
    OpenvSwitchDatabaseConnection ovsdb;

    @Inject
    HostAgentConfiguration configuration;

    private static final Logger log = LoggerFactory
        .getLogger(MidonetPortIdExecutor.class);

    protected MidonetPortIdExecutor() {
        super(UUID.class);
    }

    @Override
    public void execute()
        throws CommandExecutionFailedException {

        String vrnRouterNetworkId = configuration.getVrnRouterNetworkId();
        String externalIdKey = configuration.getMidolmanExternalIdKey();

        Set<String> bridgeNames = ovsdb.getBridgeNamesByExternalId(
            externalIdKey, vrnRouterNetworkId);

        String bridgeName = bridgeNames.iterator().next();

        if (bridgeName == null) {
            log.error(
                "Could not find a bridge which has an external id: {} => {}",
                externalIdKey, vrnRouterNetworkId);
            return;
        }

        switch (getOperationType()) {
            case SET:
                String midonetPortId = getParam().toString();

                ovsdb.addSystemPort(bridgeName, targetName)
                     .externalId(externalIdKey, midonetPortId)
                     .build();
                break;
            case CLEAR:
            case DELETE:
                ovsdb.delPort(targetName);
                break;
        }
    }
}
