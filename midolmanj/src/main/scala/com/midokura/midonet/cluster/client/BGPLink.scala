/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster.client

import com.midokura.packets.IntIPv4
import java.util.UUID

class BGPLink {
    // Identifier for this BGP configuration object.
    var bgpID: UUID = null

    // The port that owns this BGP.
    var portID: UUID = null

    // The local AS number for this BGP link, as a positive integer.
    var localAS: Int = 0

    // The IPv4 address of the peer
    var peerAddr: IntIPv4 = null

    // The peer's AS number, as a positive integer.
    var peerAS: Int = 0

    // The TCP MD5 signature to authenticate a session.
    var tcpMd5SigKey: String = null

    // The list of destinations reachable by this BGP's router,
    // to be advertised to the peer.
    var advertisedDestinations: List[IntIPv4] = null


}
