/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.cluster.LocalDataClientImpl;
import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MidoNet implementation of Neutron plugin interface.
 */
@SuppressWarnings("unused")
public class NeutronPluginImpl extends LocalDataClientImpl
        implements NeutronPlugin {

    private final static Logger log =
            LoggerFactory.getLogger(NeutronPluginImpl.class);

    @Inject
    private NetworkZkManager networkZkManager;

    @Override
    public Network createNetwork(@Nonnull Network network)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareCreateNetwork(ops, network);
        commitOps(ops);

        return getNetwork(network.id);
    }

    @Override
    public List<Network> createNetworkBulk(@Nonnull List<Network> networks)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        for (Network network : networks) {
            networkZkManager.prepareCreateNetwork(ops, network);
        }

        commitOps(ops);

        List<Network> nets = new ArrayList<>(networks.size());
        for (Network network : networks) {
            nets.add(getNetwork(network.id));
        }
        return nets;
    }

    @Override
    public void deleteNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareDeleteNetwork(ops, id);
        commitOps(ops);
    }

    @Override
    public Network getNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return networkZkManager.getNetwork(id);
    }

    @Override
    public List<Network> getNetworks()
            throws StateAccessException, SerializationException {
        return networkZkManager.getNetworks();
    }

    @Override
    public Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
            throws StateAccessException, SerializationException,
            BridgeZkManager.VxLanPortIdUpdateException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareUpdateNetwork(ops, network);

        // Throws NotStatePathException if it does not exist.
        commitOps(ops);

        return getNetwork(id);
    }

    @Override
    public Subnet createSubnet(@Nonnull Subnet subnet)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareCreateSubnet(ops, subnet);
        commitOps(ops);

        // TODO: handle external network case

        return getSubnet(subnet.id);
    }

    @Override
    public List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        for (Subnet subnet: subnets) {
            networkZkManager.prepareCreateSubnet(ops, subnet);
        }
        commitOps(ops);

        List<Subnet> newSubnets = new ArrayList<>(subnets.size());
        for (Subnet subnet : subnets) {
            newSubnets.add(getSubnet(subnet.id));
        }

        return newSubnets;
    }

    @Override
    public void deleteSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareDeleteSubnet(ops, id);
        commitOps(ops);

        // TODO: handle external network case
    }

    @Override
    public Subnet getSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return networkZkManager.getSubnet(id);
    }

    @Override
    public List<Subnet> getSubnets()
            throws StateAccessException, SerializationException {
        return networkZkManager.getSubnets();
    }

    @Override
    public Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException {

        List<Op> ops  = new ArrayList<>();
        networkZkManager.prepareUpdateSubnet(ops, subnet);

        // This should throw NoStatePathException if it doesn't exist.
        commitOps(ops);

        return getSubnet(id);
    }

    private void createPortOps(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        if (port.isVif()){

            networkZkManager.prepareCreateVifPort(ops, port);

            // TODO: SG and External network

        } else if (port.isDhcp()) {

            networkZkManager.prepareCreateDhcpPort(ops, port);

        } else {

            networkZkManager.prepareCreateNeutronPort(ops, port);

        }
    }

    @Override
    public Port createPort(@Nonnull Port port)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        createPortOps(ops, port);
        commitOps(ops);

        return getPort(port.id);
    }

    @Override
    public List<Port> createPortBulk(@Nonnull List<Port> ports)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        for (Port port : ports) {
            createPortOps(ops, port);
        }
        commitOps(ops);

        List<Port> outPorts = new ArrayList<>(ports.size());
        for (Port port : ports) {
            outPorts.add(getPort(port.id));
        }
        return outPorts;
    }

    @Override
    public void deletePort(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        Port port = getPort(id);
        if (port == null) {
            return;
        }

        List<Op> ops = new ArrayList<>();

        if(port.isVif()) {

            // TODO: handle external network, FIP disassociation and SG

            networkZkManager.prepareDeleteVifPort(ops, port);

        } else if(port.isDhcp()) {

            networkZkManager.prepareDeleteDhcpPort(ops, port);

        }

        commitOps(ops);
    }

    @Override
    public Port getPort(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return networkZkManager.getPort(id);
    }

    @Override
    public List<Port> getPorts()
            throws StateAccessException, SerializationException {
        return networkZkManager.getPorts();
    }

    @Override
    public Port updatePort(@Nonnull UUID id, @Nonnull Port port)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        // Fixed IP and security groups can be updated
        List<Op> ops = new ArrayList<>();

        if (port.isVif()) {

            // TODO handle SG

            networkZkManager.prepareUpdateVifPort(ops, port);

        } else if (port.isDhcp()) {

            networkZkManager.prepareUpdateDhcpPort(ops, port);

        }

        // This should throw NoStatePathException if it doesn't exist.
        commitOps(ops);

        return getPort(id);
    }
}
