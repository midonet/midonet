package org.midonet.api.vtep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;

import org.midonet.api.zookeeper.ExtendedZookeeperConfig;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientImpl;
import org.midonet.brain.southbound.vtep.VtepDataClientMock;
import org.midonet.packets.IPv4Addr$;

/**
 * Cannot be made a MockProvider in the tests packages because the modules deps
 * in the API are dependant on the ExtendedZookeeperConfig, but modules are not
 * customized on tests.
 */
public class VtepDataClientProvider implements Provider<VtepDataClient> {

    public static final String MOCK_VTEP_MGMT_IP = "250.132.36.225";
    public static final int MOCK_VTEP_MGMT_PORT = 12345;
    public static final String MOCK_VTEP_NAME = "Mock VTEP";
    public static final String MOCK_VTEP_DESC = "This is a mock VTEP";
    public static final Set<String> MOCK_VTEP_TUNNEL_IPS = Sets.newHashSet(
            "32.213.81.62", "197.132.120.121", "149.150.232.204");
    public static final String[] MOCK_VTEP_PORT_NAMES =
            new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};

    private final boolean useMock;
    private VtepDataClientMock mockInstance;

    @Inject
    private Provider<ConnectionService> cnxnServiceProvider;

    @Inject
    private Provider<ConfigurationService> cfgServiceProvider;

    @Inject
    private Provider<InventoryService> invServiceProvider;

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

            // Add a non-Midonet switch and binding to test that the
            // Midonet API properly ignores these. Need to call connect()
            // so the mock doesn't throw not-connected errors.
            mockInstance.connect(
                    IPv4Addr$.MODULE$.fromString(MOCK_VTEP_MGMT_IP),
                    MOCK_VTEP_MGMT_PORT);
            mockInstance.addLogicalSwitch("NonMidonetLS", 123456);
            mockInstance.bindVlan("NonMidonetLS", "eth2", 4000,
                                  123456, new ArrayList<String>());
            mockInstance.disconnect();
        }
        return mockInstance;
    }

    public VtepDataClient get() {
        return useMock ? getMockInstance() :
            new VtepDataClientImpl(cfgServiceProvider.get(),
                                   cnxnServiceProvider.get(),
                                   invServiceProvider.get());
    }
}
