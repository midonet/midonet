/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import org.midonet.license.LicenseInformation;

/**
 * Contains the extra license information.
 */
public class LicenseExtra {
    private static final int MIN_AGENT_QUOTA = 1;
    private static final int MAX_AGENT_QUOTA = Integer.MAX_VALUE;

    @Min(LicenseExtra.MIN_AGENT_QUOTA)
    @Max(LicenseExtra.MAX_AGENT_QUOTA)
    private int agentQuota;

    public LicenseExtra() { }

    public LicenseExtra(LicenseInformation info) {
        this.agentQuota = info.getAgentQuota();
    }

    public int getAgentQuota() {
        return agentQuota;
    }
}
