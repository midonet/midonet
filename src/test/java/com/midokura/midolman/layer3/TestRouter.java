package com.midokura.midolman.layer3;

import org.junit.Assert;

import java.io.IOException;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.layer4.MockNatMapping;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.RouterDirectory;

public class TestRouter {

    private Router rtr;
    private RuleEngine ruleEngine;
    private ReplicatedRoutingTable rTable;
    private PortDirectory portDir;
    private Reactor reactor;

    @Before
    public void setUp() throws KeeperException, InterruptedException,
            IOException, ClassNotFoundException {
        Directory dir = new MockDirectory();
        dir.add("/midonet", null, CreateMode.PERSISTENT);
        dir.add("/midonet/ports", null, CreateMode.PERSISTENT);
        Directory portsSubdir = dir.getSubDirectory("/midonet/ports");
        portDir = new PortDirectory(portsSubdir);
        dir.add("/midonet/routers", null, CreateMode.PERSISTENT);
        Directory routersSubdir = dir.getSubDirectory("/midonet/routers");
        RouterDirectory routerDir = new RouterDirectory(routersSubdir);
        UUID rtrId = new UUID(1234, 5678);
        routerDir.addRouter(rtrId);
        // TODO(pino): replace the following with a real implementation.
        NatMapping natMap = new MockNatMapping();
        ruleEngine = new RuleEngine(routerDir, rtrId, natMap);
        rTable = new ReplicatedRoutingTable(rtrId, routerDir
                .getRoutingTableDirectory(rtrId));
        reactor = new MockReactor();
        rtr = new Router(rtrId, ruleEngine, rTable, portDir, reactor);
    }

    @Test
    public void testDropsIPv6() {
        short IPv6_ETHERTYPE = (short)0x86DD;
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress("02:aa:bb:cc:dd:ee");
        eth.setDestinationMACAddress("02:aa:bb:cc:dd:ee");
        eth.setEtherType(IPv6_ETHERTYPE);
        eth.setPad(true);
        byte[] data = eth.serialize();
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, (short)0);
        ForwardInfo fInfo = new ForwardInfo();
        rtr.process(match, eth, fInfo);
        Assert.assertTrue(fInfo.action.equals(Action.NOT_IPV4));
    }

}
