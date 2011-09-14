
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
    public short localPort;
    public int localAS;
    public InetAddress peerAddr;
    public short peerPort;
	public int peerAS;
    public String tcpMd5SigKey;
    public InetAddress[] advertisedNetworkAddrs;
	// This should be Byte[] not byte[] because of Jackson will produce a byte array
	// string in the case of you specify this propoerty as byte[].
    public Byte[] advertisedNetworkLengths;

	public BGP(short localPort, int localAS, InetAddress peerAddr,
               short peerPort, int peerAS, String tcpMd5SigKey,
               InetAddress[] advertisedNetworkAddrs,
               Byte[] advertisedNetworkLengths) {
        this.localPort = localPort;
        this.localAS = localAS;
        this.peerAddr = peerAddr;
        this.peerPort = peerPort;
		this.peerAS = peerAS;
        this.tcpMd5SigKey = tcpMd5SigKey;
        this.advertisedNetworkAddrs = advertisedNetworkAddrs;
        this.advertisedNetworkLengths = advertisedNetworkLengths;
    }

	// Default constructor for the Jackson deserialization.
	public BGP() { super(); }
}
