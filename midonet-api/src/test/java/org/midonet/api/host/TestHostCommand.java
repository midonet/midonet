/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.api.host;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.client.VendorMediaType;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostCommand;
import org.midonet.client.dto.DtoInterface;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test cases to validate the update/create interface functionality of the
 * Interface management REST api.
 */
public class TestHostCommand extends JerseyTest {

    private HostZkManager hostManager;
    private ClientResponse response;

    private DtoHost dtoHost;
    private URI baseUri;

    public TestHostCommand() {
        super(createWebApp());
    }

    private static AppDescriptor createWebApp() {

        return FuncTest.getBuilder().build();

    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() throws KeeperException,
            StateAccessException,
            InterruptedException,
            SerializationException {
        DtoApplication application = resource()
            .accept(VendorMediaType.APPLICATION_JSON_V5)
            .get(DtoApplication.class);


        baseUri = application.getUri();
        hostManager = JerseyGuiceTestServletContextListener.getHostZkManager();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testHost");

        UUID hostId = UUID.randomUUID();

        hostManager.createHost(hostId, metadata);

        dtoHost = resource()
            .uri(ResourceUriBuilder.getHost(baseUri, hostId))
            .type(VendorMediaType.APPLICATION_HOST_JSON_V3).get(DtoHost.class);

        assertThat("We should have been able to create a new host",
                   dtoHost,
                   allOf(notNullValue(), hasProperty("id", equalTo(hostId))));

        DtoInterface[] interfaces = dtoHost.getHostInterfaces();
        assertThat("There was no interface returned", interfaces, nullValue());
    }

    @Test
    public void testCreateUnnamedInterfaceShouldFail() throws Exception {

        DtoInterface dtoInterface = new DtoInterface();

        // no properties
        response = resource()
            .uri(ResourceUriBuilder.getHostInterfaces(baseUri, dtoHost.getId()))
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .post(ClientResponse.class, dtoInterface);

        assertThat("The API response should signal a validation failure.",
                   response,
                   allOf(notNullValue(), hasProperty("status", equalTo(400))));
    }

    @Test
    public void testCreateNamedInterfaceWithoutTypeShouldFail()
        throws Exception {
        DtoInterface dtoInterface = new DtoInterface();
        dtoInterface.setName("test");

        // with name should pass
        response = resource()
            .uri(ResourceUriBuilder.getHostInterfaces(baseUri, dtoHost.getId()))
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .post(ClientResponse.class, dtoInterface);

        assertThat(
            "Request should failed when the name is set but not type is set.",
            response,
            allOf(notNullValue(), hasProperty("status", equalTo(400))));
    }

    @Test
    public void testCreateNamedAndTypedAsVirtualInterfaceShouldPass()
        throws Exception {
        DtoInterface dtoInterface = new DtoInterface();
        dtoInterface.setName("test");
        dtoInterface.setType(DtoInterface.Type.Tunnel);

        DtoHostCommand dtoHostCommand = resource()
            .uri(ResourceUriBuilder.getHostInterfaces(baseUri, dtoHost.getId()))
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .post(DtoHostCommand.class, dtoInterface);

        assertThat(
            "Request should success when we want to create a named and typed interface.",
            dtoHostCommand, notNullValue());
    }

    @Test
    public void testCreateNamedAndTypedAsPhysicalInterfaceShouldFail()
        throws Exception {
        DtoInterface dtoInterface = new DtoInterface();
        dtoInterface.setName("test");
        dtoInterface.setType(DtoInterface.Type.Physical);

        response = resource()
            .uri(ResourceUriBuilder.getHostInterfaces(baseUri, dtoHost.getId()))
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .post(ClientResponse.class, dtoInterface);

        assertThat(
            "Request should success when we want to create a named and typed interface.",
            response,
            allOf(notNullValue(), hasProperty("status", equalTo(400))));
    }

    @Test
    public void testUpdateInterfaceTypeShouldFail() throws Exception {

        HostDirectory.Interface anInterface = new HostDirectory.Interface();
        anInterface.setName("test");

        DtoInterface currentInterface = saveInterface(anInterface);
        currentInterface.setType(DtoInterface.Type.Tunnel);
        response = resource()
            .uri(currentInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(ClientResponse.class, currentInterface);

        assertThat(
            "Request should complain (not type changes on an active interface).",
            response,
            allOf(notNullValue(), hasProperty("status", equalTo(400))));
    }

    @Test
    public void testUpdateInterfaceNameShouldFail() throws Exception {

        HostDirectory.Interface anInterface = new HostDirectory.Interface();
        anInterface.setName("test");

        DtoInterface currentInterface = saveInterface(anInterface);
        currentInterface.setName("mandinga");
        response = resource()
            .uri(currentInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(ClientResponse.class, currentInterface);

        assertThat(
            "Request should complain (not type changes on an active interface).",
            response,
            allOf(notNullValue(), hasProperty("status", equalTo(400))));
    }

    @Test
    public void testUpdateInterfaceMtuShouldPass() throws Exception {

        HostDirectory.Interface anInterface = new HostDirectory.Interface();
        anInterface.setName("test");
        anInterface.setMtu(200);

        DtoInterface currentInterface = saveInterface(anInterface);

        currentInterface.setMtu(100);
        DtoHostCommand dtoHostCommand = resource()
            .uri(currentInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(DtoHostCommand.class, currentInterface);

        assertThat("Request should return a command.",
                   dtoHostCommand, notNullValue());
    }

    @Test
    public void testUpdateInterfaceMacShouldPass() throws Exception {
        HostDirectory.Interface anInterface = new HostDirectory.Interface();
        anInterface.setName("test");
        anInterface.setMac(MAC.fromString("11:11:11:11:11:11").getAddress());

        DtoInterface currentInterface = saveInterface(anInterface);

        currentInterface.setMac("11:11:11:11:11:12");
        DtoHostCommand dtoHostCommand = resource()
            .uri(currentInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(DtoHostCommand.class, currentInterface);

        assertThat("Request should return a command.",
                   dtoHostCommand, notNullValue());
    }

    @Test
    public void testUpdateInterfaceSetUpStatusShouldPass() throws Exception {
        HostDirectory.Interface anInterface = new HostDirectory.Interface();
        anInterface.setName("test");
        anInterface.setStatus(0);

        DtoInterface current = saveInterface(anInterface);

        current.setStatusField(DtoInterface.StatusType.Up);
        DtoHostCommand dtoHostCommand = resource()
            .uri(current.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(DtoHostCommand.class, current);

        assertThat("Request should return a command",
                   dtoHostCommand, notNullValue());
    }

    @Test
    public void testUpdateInterfaceSetCarrierStatusShouldFail()
        throws Exception {
        HostDirectory.Interface anInterface = new HostDirectory.Interface();
        anInterface.setName("test");
        anInterface.setStatus(0);

        DtoInterface current = saveInterface(anInterface);

        current.setStatusField(DtoInterface.StatusType.Carrier);
        response = resource()
            .uri(current.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(ClientResponse.class, current);

        assertThat("Request should return and http error code",
                   response,
                   anyOf(notNullValue(), hasProperty("status", equalTo(400))));
    }

    private DtoInterface saveInterface(HostDirectory.Interface anInterface)
        throws StateAccessException, IOException, SerializationException {
        hostManager.createInterface(dtoHost.getId(), anInterface);

        // no properties
        DtoInterface[] interfaces = resource()
            .uri(ResourceUriBuilder.getHostInterfaces(baseUri, dtoHost.getId()))
            .type(VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(DtoInterface[].class);

        assertThat("There is only one ! (interface)",
                   interfaces,
                   allOf(notNullValue(), arrayWithSize(1)));

        return interfaces[0];
    }
}
