package com.midokura.midonet.smoketest;

import com.jcraft.jsch.*;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;
import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import com.midokura.tools.process.ProcessHelper;
import org.apache.commons.io.IOUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.midokura.tools.process.ProcessHelper.newProcess;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 12:16 PM
 */
public class VmPingTest {

    private final static Logger log = LoggerFactory.getLogger(VmPingTest.class);

    static Tenant tenant;
    static TapPort tapPort;
    static InternalPort internalPort;
    static MidolmanMgmt mgmt;

    static OpenvSwitchDatabaseConnection ovsdb;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch", "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);

        String tapPortName = "tapPort1";

        // First clean up left-overs from previous incomplete tests.
        newProcess(String.format("sudo -n ip tuntap del dev %s mode tap", tapPortName))
                .logOutput(log, "remove_old_tap")
                .runAndWait();

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        try {
            mgmt.deleteTenant("tenant1");
        } catch (Exception e) {
        }

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();

        Router router = tenant.addRouter().setName("rtr1").build();

        tapPort = router.addPort(ovsdb)
                .setDestination("192.168.100.2")
                .setOVSPortName(tapPortName)
                .buildTap();

        internalPort = router.addPort(ovsdb)
                .setDestination("192.168.100.3")
                .buildInternal();

        newProcess(String.format("sudo -n route add -net 192.168.100.0/24 dev %s", internalPort.getName()))
                .logOutput(log, "add_host_route")
                .runAndWait();

        Thread.sleep(1000);
    }

    @Test
    public void test() throws IOException, InterruptedException {
        LibvirtHandler handler = LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template_x86_64");

        VMController vm =
                handler.newDomain()
                        .setDomainName("test_ssh_domain")
                        .setHostName("test")
                        .setNetworkDevice(tapPort.getName())
                        .build();

        vm.startup();

        // validate ping to the 192.168.100.2 address
        // validate ssh to the 192.168.100.2 address


        try {
            JSch jsch = new JSch();
            Session session=jsch.getSession("ubuntu", "192.168.100.2", 22);

            // username and password will be given via UserInfo interface.
            UserInfo ui=new UserInfo() {
                @Override
                public String getPassphrase() {
                    return null;
                }

                @Override
                public String getPassword() {
                    return "ubuntu";
                }

                @Override
                public boolean promptPassword(String s) {
                    return true;
                }

                @Override
                public boolean promptPassphrase(String s) {
                    return false;
                }

                @Override
                public boolean promptYesNo(String s) {
                    return true;
                }

                @Override
                public void showMessage(String s) {
                    int a = 10;
                }
            };

            session.setUserInfo(ui);
            // try to connect to the vm for a maximum of 10 seconds
            session.connect(60 * 1000);

            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand("hostname");
            channelExec.connect();
            String result = IOUtils.toString(channelExec.getInputStream(), "UTF-8");

            int a = 10;

        } catch (JSchException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

    }
}
