package org.midonet.midolman.host.services;

import org.junit.Test;

import org.midonet.midolman.host.state.HostDirectory;

import static org.junit.Assert.assertEquals;

public class LegacyHostServiceTest extends HostServiceTest {

    public LegacyHostServiceTest() throws Exception {
        super(false);
    }

    @Test
    public void createsNewZkHost() throws Throwable {
        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfAlive() throws Throwable {
        HostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfNotAlive() throws Throwable {
        startAndStopService();
        zkManager.deleteEphemeral(alivePath);
        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostVersion() throws Throwable {
        startAndStopService();
        zkManager.deleteEphemeral(versionPath);
        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostAndUpdatesMetadataInLegacyStore() throws Throwable {
        startAndStopService();

        updateMetadata();
        zkManager.deleteEphemeral(alivePath);

        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test(expected = HostService.HostIdAlreadyInUseException.class)
    public void recoversZkHostLoopsIfMetadataIsDifferent()
        throws Throwable {
        startAndStopService();

        updateMetadata();

        startAndStopService();
    }

    private void assertHostState() throws Exception {
        assertEquals(hostZkManager.exists(hostId), true);
        assertEquals(hostZkManager.isAlive(hostId), true);
        assertEquals(hostZkManager.get(hostId).getName(), hostName);
        assertEquals(zkManager.exists(versionPath), true);
    }

    private void updateMetadata() throws Exception {
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("name");
        hostZkManager.updateMetadata(hostId, metadata);
    }
}
