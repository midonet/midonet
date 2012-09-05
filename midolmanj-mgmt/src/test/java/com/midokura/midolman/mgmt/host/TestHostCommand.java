/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.host;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import com.midokura.midolman.host.commands.executors.CommandProperty;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostDirectory.Command.AtomicCommand.OperationType;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.FuncTest;
import com.midokura.midolman.mgmt.zookeeper.StaticMockDirectory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.dto.DtoHost;
import com.midokura.midonet.client.dto.DtoHostCommand;
import com.midokura.midonet.client.dto.DtoInterface;
import com.midokura.midonet.client.dto.DtoInterface.PropertyKeys;
import com.midokura.packets.MAC;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_HOST_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON;

/**
 * Test cases to validate the update/create interface functionality of the
 * Interface management REST api.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/20/12
 */
public class TestHostCommand extends JerseyTest {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    private HostZkManager hostManager;
    private Directory rootDirectory;
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
    public void before() throws KeeperException, StateAccessException {
        DtoApplication application = resource()
            .type(VendorMediaType.APPLICATION_JSON)
            .get(DtoApplication.class);

        baseUri = application.getUri();
        rootDirectory = StaticMockDirectory.getDirectoryInstance();

        hostManager = new HostZkManager(rootDirectory, "/test/midolman");

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testHost");

        UUID hostId = UUID.randomUUID();

        hostManager.createHost(hostId, metadata);

        dtoHost = resource()
            .uri(ResourceUriBuilder.getHost(baseUri, hostId))
            .type(APPLICATION_HOST_JSON).get(DtoHost.class);

        assertThat("We should have been able to create a new host",
                   dtoHost,
                   allOf(notNullValue(), hasProperty("id", equalTo(hostId))));

        ClientResponse interfacesResponse = resource()
            .uri(dtoHost.getInterfaces())
            .type(APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(ClientResponse.class);

        assertThat("There should new no interfaces for this new host",
                   interfacesResponse,
                   allOf(notNullValue(),
                         hasProperty("clientResponseStatus", equalTo(
                             Status.OK))));
        DtoInterface[] interfaces = interfacesResponse.getEntity(
                DtoInterface[].class);
        assertThat("There was no interface returned", interfaces, emptyArray());
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDirectory.clearDirectoryInstance();
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

    @Test
    public void testHostCommandCreate() throws Exception {

        DtoInterface dtoInterface = new DtoInterface();
        dtoInterface.setName("eth1");
        dtoInterface.setType(DtoInterface.Type.Virtual);

        response = resource()
            .uri(dtoHost.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .post(ClientResponse.class, dtoInterface);

        assertThat("The interface creation call should have returned HTTP 200.",
                   response,
                   allOf(notNullValue(),
                         hasProperty("clientResponseStatus",
                                     equalTo(Status.OK))));

        dtoInterface.setName("eth2");
        response = resource()
            .uri(dtoHost.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .post(ClientResponse.class, dtoInterface);

        assertThat("The interface creation call should have returned HTTP 200.",
                   response,
                   allOf(notNullValue(),
                         hasProperty("clientResponseStatus",
                                     equalTo(Status.OK))));

        DtoHostCommand[] hostCommands = resource()
            .uri(dtoHost.getHostCommands())
            .type(VendorMediaType.APPLICATION_HOST_COMMAND_COLLECTION_JSON)
            .get(DtoHostCommand[].class);

        assertThat("We should have two host commands returned",
                   hostCommands,
                   allOf(notNullValue(), arrayWithSize(2)));
    }

    @Test
    public void testSetProperty() throws Exception {
        UUID hostId = UUID.randomUUID();
        UUID portId = UUID.randomUUID();

        HostDirectory.Metadata hostMetadata = new HostDirectory.Metadata();
        hostMetadata.setName("host1");

        hostManager.createHost(hostId, hostMetadata);
        HostDirectory.Interface hostInterface = new HostDirectory.Interface();
        hostInterface.setName("test");

        hostManager.createInterface(hostId, hostInterface);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(VendorMediaType.APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        DtoInterface[] interfaces = resource()
            .uri(host.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(DtoInterface[].class);

        assertThat("There should be one interface description for the host",
                   interfaces, arrayWithSize(1));

        DtoInterface dtoHostInterface = interfaces[0];

        //  set the port id (and verify the result)
        dtoHostInterface.setProperty(PropertyKeys.midonet_port_id,
                                     portId.toString());

        response = resource()
            .uri(dtoHostInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(ClientResponse.class, dtoHostInterface);

        assertThat("The client response should be a 200 response",
                   response.getClientResponseStatus(), is(Status.OK));

        DtoHostCommand hostCommand = response.getEntity(DtoHostCommand.class);

        HostDirectory.Command commandData =
            hostManager.getCommandData(host.getId(), hostCommand.getId());

        assertThat("The command has the proper size",
                   commandData.getCommandList(), hasSize(1));

        assertThat("The command contains the proper atomic command",
                   commandData.getCommandList(),
                   contains(
                       allOf(
                           hasProperty("property",
                                       is(CommandProperty.midonet_port_id)),
                           hasProperty("value", is(portId.toString())),
                           hasProperty("opType", is(OperationType.SET))
                       )));
    }

    @Test
    public void testClearProperty() throws Exception {
        UUID hostId = UUID.randomUUID();
        UUID portId = UUID.randomUUID();

        HostDirectory.Metadata hostMetadata = new HostDirectory.Metadata();
        hostMetadata.setName("host1");

        hostManager.createHost(hostId, hostMetadata);
        HostDirectory.Interface hostInterface = new HostDirectory.Interface();
        hostInterface.setName("test");
        hostInterface.getProperties().put(PropertyKeys.midonet_port_id.name(),
                                          portId.toString());

        hostManager.createInterface(hostId, hostInterface);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(VendorMediaType.APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        DtoInterface[] interfaces = resource()
            .uri(host.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(DtoInterface[].class);

        assertThat("There should be one interface description for the host",
                   interfaces, arrayWithSize(1));

        DtoInterface dtoHostInterface = interfaces[0];

        assertThat(dtoHostInterface.getProperties(),
                   hasEntry(
                       is(PropertyKeys.midonet_port_id.toString()),
                       is(portId.toString())
                   ));

        // clear the port id
        dtoHostInterface.setProperty(PropertyKeys.midonet_port_id, "");
        response = resource()
            .uri(dtoHostInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .put(ClientResponse.class, dtoHostInterface);

        assertThat("The client response should be a 200 response",
                   response.getClientResponseStatus(), is(Status.OK));

        DtoHostCommand hostCommand = response.getEntity(DtoHostCommand.class);

        HostDirectory.Command commandData =
            hostManager.getCommandData(host.getId(), hostCommand.getId());

        assertThat("The generated command has the proper size",
                   commandData.getCommandList(), hasSize(1));

        assertThat("The command contains the proper atomic command",
                   commandData.getCommandList(),
                   contains(
                       allOf(
                           hasProperty("property",
                                       is(CommandProperty.midonet_port_id)),
                           hasProperty("value", is("")),
                           hasProperty("opType", is(OperationType.CLEAR))
                       )));
    }

    private DtoInterface saveInterface(HostDirectory.Interface anInterface)
        throws StateAccessException, IOException {
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
