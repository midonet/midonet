/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.services.HostIdProviderService;

/**
 * Date: 6/1/12
 */
public class HostKeyService  {

    @Inject
    HostIdProviderService idProviderService;

    private final static Logger log =
        LoggerFactory.getLogger(HostKeyService.class);

    public String getHostId() {
        if (idProviderService != null && idProviderService.getHostId() != null) {
            log.info("Returned Id {}", idProviderService.getHostId());
            return idProviderService.getHostId().toString();
        }


        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Error when trying to get the host name", e);
            return "UNKNOWN";
        }
    }
}
