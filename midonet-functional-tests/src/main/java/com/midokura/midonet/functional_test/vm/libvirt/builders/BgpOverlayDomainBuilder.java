/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.vm.libvirt.builders;

import com.midokura.midonet.functional_test.vm.HypervisorType;
import com.midokura.midonet.functional_test.vm.libvirt.LibvirtHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 11/30/11
 * Time: 11:12 AM
 */
public class BgpOverlayDomainBuilder extends AbstractOverlayDomainBuilder<BgpOverlayDomainBuilder> {
    private final static Logger log = LoggerFactory.getLogger(BgpOverlayDomainBuilder.class);

    int localAS;
    int peerAS;
//    String peerIP;

    public BgpOverlayDomainBuilder(HypervisorType hypervisorType, String connectUri,
                                   String baseImage, String templateName,
                                   LibvirtHandler.Configuration configuration) {
        super(hypervisorType, connectUri, baseImage, templateName, configuration);
    }

    public BgpOverlayDomainBuilder setLocalAS(int localAS) {
        this.localAS = localAS;
        return self();
    }

    public BgpOverlayDomainBuilder setPeerAS(int peerAS) {
        this.peerAS = peerAS;
        return self();
    }

//    public BgpOverlayDomainBuilder setPeerIP(String peerIP) {
//        this.peerIP = peerIP;
//        return self();
//    }

    @Override
    protected String buildOverlayImage() {

        File ovlFile = buildTargetOverlayFile();

        try {

            int commandReturnCode = executeToolBasedCommand(
                "create_bgp_overlay.sh",
                getBaseImage() + " " +
                    getHostName() + " " + ovlFile.getAbsolutePath() + " " +
                    localAS + " " + peerAS);

            if (commandReturnCode == 0) {
                return ovlFile.getAbsolutePath();
            }

        } catch (IOException e) {
            log.error("Exception while creating overlay image for domain: " + getDomainName(), e);
        } catch (InterruptedException e) {
            log.error("Exception while creating overlay image for domain: " + getDomainName(), e);
        }

        return null;
    }

    @Override
    protected BgpOverlayDomainBuilder self() {
        return this;
    }
}
