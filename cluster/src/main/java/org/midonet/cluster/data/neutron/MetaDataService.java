/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.packets.IPv4Subnet;

public class MetaDataService {

    public static final String IPv4_ADDRESS =  "169.254.169.254/32";
    public static final IPv4Subnet IPv4_SUBNET =  new IPv4Subnet(IPv4_ADDRESS);

}
