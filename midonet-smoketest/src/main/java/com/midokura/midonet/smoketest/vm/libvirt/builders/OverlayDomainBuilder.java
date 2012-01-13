/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.vm.libvirt.builders;

import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/30/11
 * Time: 10:40 AM
 */
public class OverlayDomainBuilder extends AbstractOverlayDomainBuilder<OverlayDomainBuilder> {

    private final static Logger log = LoggerFactory.getLogger(OverlayDomainBuilder.class);

    public OverlayDomainBuilder(HypervisorType hypervisorType, String connectUri,
                                String baseImage, String templateName,
                                LibvirtHandler.Configuration configuration) {
        super(hypervisorType, connectUri, baseImage, templateName, configuration);
    }

    @Override
    protected OverlayDomainBuilder self() {
        return this;
    }

    @Override
    protected String buildOverlayImage() {

        File ovlFile = buildTargetOverlayFile();

        try {

            int commandReturnCode = executeToolBasedCommand(
                "create_domain_overlay.sh",
                getBaseImage() + " " + getHostName() + " " + ovlFile.getAbsolutePath());

            if (commandReturnCode == 0 ) {
                return ovlFile.getAbsolutePath();
            }
        } catch (IOException e) {
            log.error("Exception while creating overlay image for domain: " + getDomainName(), e);
        } catch (InterruptedException e) {
            log.error("Exception while creating overlay image for domain: " + getDomainName(), e);
        }

        return null;
    }
}
