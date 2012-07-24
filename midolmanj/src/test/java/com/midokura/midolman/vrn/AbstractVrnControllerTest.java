/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.vrn;

import java.util.Collections;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFPhysicalPort;

import com.midokura.midolman.Setup;
import com.midokura.util.eventloop.MockReactor;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.portservice.NullPortService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MockCache;
import static com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

/**
 * Base class which can instantiate a proper VRNController and allows access to
 * various objects and builders for VRN data.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/3/12
 */
public abstract class AbstractVrnControllerTest {

    Directory baseDirectory;
    VRNController controller;
    ZkPathManager pathManager;

    MockOpenvSwitchDatabaseConnection ovsDbConnection;

    private RouterZkManager routerManager;
    private PortZkManager portManager;

    protected Cache createCache() {
        return new MockCache();
    }

    protected NullPortService createPortService() {
        return new NullPortService();
    }

    protected Directory createBaseDirectory()
        throws InterruptedException, KeeperException {

        Directory directory = new MockDirectory();

        directory.add(getBaseDirectoryPath(), null, CreateMode.PERSISTENT);

        Setup.createZkDirectoryStructure(directory, getBaseDirectoryPath());

        return directory;
    }

    private ControllerStub createControllerStub() {
        MockControllerStub mockControllerStub = new MockControllerStub();

        OFFeaturesReply features = new OFFeaturesReply();

        features.setDatapathId(getDatapathId());
        features.setPorts(Collections.<OFPhysicalPort>emptyList());

        mockControllerStub.setFeatures(features);

        return mockControllerStub;
    }

    protected String getExternalIdKey() {
        return "midonet";
    }

    protected String getBaseDirectoryPath() {
        return "/midonet";
    }

    protected int getDatapathId() {
        return 47;
    }

    protected UUID getSwitchUUID() {
        return UUID.fromString("01234567-0123-0123-aaaa-0123456789ab");
    }

    protected IntIPv4 getLocalIpAddress() {
        return IntIPv4.fromString("192.168.1.50");
    }

    protected RouterZkManager getRouterManager() {
        if (routerManager == null) {
            routerManager = new RouterZkManager(baseDirectory,
                                                getBaseDirectoryPath());
        }

        return routerManager;
    }

    protected PortZkManager getPortManager() {
        if (portManager == null) {
            portManager = new PortZkManager(baseDirectory,
                                            getBaseDirectoryPath());
        }

        return portManager;
    }

    protected MockOpenvSwitchDatabaseConnection getOpenvSwitchDatabaseConnection() {
        return ovsDbConnection;
    }

    protected VRNController getVrnController() {
        return controller;
    }

    /**
     * Vrn management helper methods.
     *
     * @return the UUID of the new router.
     */
    protected UUID createNewRouter() throws StateAccessException {
        return getRouterManager().create();
    }

    protected UUID createNewPort(UUID routerId, PortConfig portConfig)
        throws StateAccessException {
        portConfig.device_id = routerId;

        return getPortManager().create(portConfig);
    }

    protected MaterializedRouterPortConfig newMaterializedPort(UUID routerId,
                                                               int portNum,
                                                               byte[] mac)
        throws StateAccessException {
        MaterializedRouterPortConfig portConfig =
            new MaterializedRouterPortConfig(routerId,
                                             0, 0, 0,
                                             new MAC(mac),
                                             null,
                                             0, 0,
                                             null);

        UUID portUuid = createNewPort(routerId, portConfig);

        getOpenvSwitchDatabaseConnection()
            .setPortExternalId(getDatapathId(),
                               portNum,
                               getExternalIdKey(),
                               portUuid.toString());

        return portConfig;
    }

    protected UUID getPortExternalId(OFPhysicalPort port) {
        return UUID.fromString(
            getOpenvSwitchDatabaseConnection()
                .getPortExternalId(
                    getDatapathId(),
                    port.getPortNumber(),
                    getExternalIdKey()));
    }


    public void setUp() throws Exception {
        ovsDbConnection = new MockOpenvSwitchDatabaseConnection();

        baseDirectory = createBaseDirectory();

        pathManager = new ZkPathManager(getBaseDirectoryPath());

        Directory portLocationDirectory =
            baseDirectory.getSubDirectory(
                pathManager.getVRNPortLocationsPath());

        PortToIntNwAddrMap portLocMap =
            new PortToIntNwAddrMap(portLocationDirectory);

        portLocMap.start();

        controller = new VRNController(
            baseDirectory, getBaseDirectoryPath(),
            getLocalIpAddress(),
            ovsDbConnection /* ovsdb */,
            new MockReactor(),
            createCache(),
            getExternalIdKey(),
            getSwitchUUID(), false,
            createPortService(), new NullPortService(), 1450);

        // register the bridge metadata in ovs
        ovsDbConnection.setDatapathExternalId(getDatapathId(),
                                              getExternalIdKey(),
                                              getSwitchUUID().toString());

        // set the stub
        controller.setControllerStub(createControllerStub());

        // simulate the bridge connection
        controller.onConnectionMade();
    }

    public void tearDown() throws Exception {
        routerManager = null;
        portManager = null;
    }

    protected OFPhysicalPort toOFPhysicalPort(int portNum, String portName,
                                            MaterializedRouterPortConfig portConfig) {

        OFPhysicalPort physicalPort = new OFPhysicalPort();

        physicalPort.setHardwareAddress(portConfig.getHwAddr().getAddress());
        physicalPort.setPortNumber((byte) portNum);
        physicalPort.setName(portName);

        return physicalPort;
    }
}
