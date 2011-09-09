package com.midokura.midolman.state;

import java.net.InetAddress;

public class BGP {
    /*
    The bgp is a list of BGP information dictionaries enabled on this
    port.  The keys for the dictionary are:

        local_port: local TCP port number for BGP, as a positive
        integer.
        local_as: local AS number that belongs to, as a positive
        integer.
        peer_addr: IPv4 address of the peer, as a human-readable
        string.
        peer_port: TCP port number at the peer, as a positive integer,
        as a string.
        tcp_md5sig_key: TCP MD5 signature to authenticate a session.
        ad_routes: A list of routes to be advertised.  Each item is a
        list [address, length] that represents a network prefix, where
        address is an IPv4 address as a human-readable string, and
        length is a positive integer.
        */
    short localPort;
    int localAS;
    InetAddress peerAddr;
    short peerPort;
    String tcpMd5SigKey;
    InetAddress[] advertisedNetworkAddrs;
    byte[] advertisedNetworkLengths;

	// Default constructor for the Jackson deserialization.
	public BGP() { super(); }
}
