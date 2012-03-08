/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.agent.modules.AbstractAgentModule;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.ZkConnection;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class TestOvsDbInterfaceSensor {


    OpenvSwitchDatabaseConnection ovsDBConnection;
    OvsDbInterfaceSensor ovsDbInterfaceSensor;

    @Before
    public void setupGuice() {

        ovsDBConnection = new MockOpenvSwitchDatabaseConnection();

        Injector injector = Guice.createInjector(new AbstractAgentModule() {
            @Override
            protected void configure() {
                super.configure();
            }

            @Provides
            HostAgentConfiguration buildConfigurationObject() {
                return null;
            }

            @Provides
            ZkConnection buildZkConnection() {
                return null;
            }


            @Provides
            OpenvSwitchDatabaseConnection buildOpenvSwitch() {
                return ovsDBConnection;
            }

            @Provides
            Directory buildDirectory() {
                return new MockDirectory();
            }
        });


        ovsDbInterfaceSensor = injector.getInstance(OvsDbInterfaceSensor.class);
    }

    @Test
    public void testUpdateInterfaceData() throws Exception {
        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        interfaces.add(new InterfaceDescription("testBridge0"));
        interfaces.add(new InterfaceDescription("testInterface0"));

        BridgeBuilder bridgeBuilder = ovsDBConnection.addBridge("testBridge0");
        bridgeBuilder.build();

        PortBuilder portBuilder = ovsDBConnection.addSystemPort("testBridge0", "testInterface0");
        portBuilder.build();

        List<InterfaceDescription> updatedInterfaces = ovsDbInterfaceSensor.updateInterfaceData(interfaces);

        assertThat(updatedInterfaces.size(), equalTo(2));
        assertThat(updatedInterfaces.get(0).getEndpoint(), equalTo(InterfaceDescription.Endpoint.BRIDGE));
        assertThat(updatedInterfaces.get(1).getEndpoint(), equalTo(InterfaceDescription.Endpoint.UNKNOWN));
    }

}
