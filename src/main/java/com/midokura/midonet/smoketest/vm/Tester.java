package com.midokura.midonet.smoketest.vm;

import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/15/11
 * Time: 5:37 PM
 */
public class Tester {
    public static void main(String[] args) {
        LibvirtHandler handler = LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template");
        handler.setTemplateImage("/home/mtoader/ubuntu-kvm/tmppxcR6r.qcow2");

        VMController controller =
                handler.newDomain()
                .setName("test_domain4")
                .setNetworkDeviceName("tap1")
                .build();

        String mac = controller.getNetworkMacAddress();
        System.out.println("Mac: " + mac);
    }
}
