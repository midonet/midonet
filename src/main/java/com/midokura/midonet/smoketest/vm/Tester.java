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

        handler.setTemplate("basic_template_x86_64");

        VMController controller =
                handler.newDomain()
                        .setDomainName("test_domain9")
                        .setHostName("hostname")
                        .setNetworkDevice("tap1")
                        .build();

        String mac = controller.getNetworkMacAddress();

//        controller.startup();
    }
}
