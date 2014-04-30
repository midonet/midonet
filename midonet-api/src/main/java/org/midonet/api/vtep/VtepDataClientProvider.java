package org.midonet.api.vtep;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import org.midonet.api.zookeeper.ExtendedZookeeperConfig;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientImpl;
import org.midonet.brain.southbound.vtep.VtepDataClientMock;

import java.util.Arrays;
import java.util.Set;

public class VtepDataClientProvider {

    public static final String MOCK_VTEP_MGMT_IP = "250.132.36.225";
    public static final int MOCK_VTEP_MGMT_PORT = 12345;
    public static final String MOCK_VTEP_NAME = "Mock VTEP";
    public static final String MOCK_VTEP_DESC = "This is a mock VTEP";
    public static final Set<String> MOCK_VTEP_TUNNEL_IPS = Sets.newHashSet(
            "32.213.81.62", "197.132.120.121", "149.150.232.204");
    public static final String[] MOCK_VTEP_PORT_NAMES =
            new String[]{"eth0", "eth1", "eth2"};

    private final boolean useMock;
    private VtepDataClientMock mockInstance;

    @Inject
    public VtepDataClientProvider(ExtendedZookeeperConfig zkConfig) {
        useMock = zkConfig.getUseMock();
    }

    private VtepDataClientMock getMockInstance() {
        if (mockInstance == null) {
            mockInstance = new VtepDataClientMock(
                    MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT,
                    MOCK_VTEP_NAME, MOCK_VTEP_DESC,
                    MOCK_VTEP_TUNNEL_IPS,
                    Arrays.asList(MOCK_VTEP_PORT_NAMES));
        }
        return mockInstance;
    }

    public VtepDataClient makeClient() {
        return useMock ? getMockInstance() : new VtepDataClientImpl();
    }
}
