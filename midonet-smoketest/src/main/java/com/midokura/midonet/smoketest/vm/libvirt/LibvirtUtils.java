/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.smoketest.vm.libvirt;

import com.midokura.midonet.smoketest.vm.HypervisorType;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/16/11
 * Time: 10:22 AM
 */
public class LibvirtUtils {

    public static String uriForHypervisorType(HypervisorType vmType) {
        switch (vmType) {
            case Kvm:
            case Qemu:
                return "qemu:///system";
            case Xen:
                return "xen:///";
            case VBox:
                return "vbox:///session";
        }

        return "";
    }
}
