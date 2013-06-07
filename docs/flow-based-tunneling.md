## Flow-based Tunneling: OVS Kernel Module and Midolman Control Plane

### Motivation

Traditionally, tunnel set-ups are port-based, that is, a tunnel port is created with fixed encapsulations, then all packet emitted from that port would be encapsulated with the same header.

Starting from version 1.9.0, OpenVSwitch (OVS) supports flow-based tunneling, a technique in which fields in tunnel outer header encapsulation can be defined per flow, instead of per port. By OVS version 1.10, port-based tunneling will no longer be supported.

The most significant benefit of flow-based tunneling is the ability to convey information to the underlay - for example, whether the overlay wants to fragment the packet, or passing information about the QoS (via DSCP marking) for a particular flow can now be selected on a per flow basis

### OpenVSwitch's implementation of flow-based tunneling
Userspace sets flow attributes as it is today, and one of the actions of a flow match would still be output action and the associated port. But instead of actually having to identify the egress tunnel vport, a universal tunnel vport would be use for each encapsulation type (GRE, VxLAN, GRE64 ...etc). Tunnel peer information would then be set as part of a SET attribute where the following fields can be set:

    1. Tunnel id: up to 64 bits of metadata
    2. Source IPv4 address of the outer header
    3. Destination IPv4 address of the outer header
    4. Tunnel flags: three are defined at this point
        a. don't fragment (DF)
        b. checkum: whether application requests tunnel header checksum
        c. key: whether application requests metadata encapulated in tunnel
            header
    5. IPv4 ToS: DSCP marking or TOS/priority marking for outer header
    6. IPv4 TTL: Time to Live field for outer header

Such information would be stored in sw_flow_key->tun_key (for matching, for tunnel ingress) as well as sw_flow_actions->actions (egress encapsulation)

The packet flow for packet to be emitted to a tunnel vport as follows:
ovs_vport_receive
    ---> ovs_dp_process_received_packet
        ---> ovs_execute_actions

    The packet buffer (type sk_buff, a kernel packet buffer, usually named skb) contains a control block pointer for different types of application to embed information into the individual packet. OVS embeds a type ovs_skb_cb type in the packet buffer for quick access to per packet control data. In ovs_execute_actions, OVS zeroes out the skb->cb->tun_key field (tun_key is of type ovs_key_ipv4_tunnel, which contains the fields above), then executes the set of actions associated with this flow key. 
    
    Then in execute_set_action, OVS would simply rewrite skb->cb->tun_key to the action attribute tunnel key structure that were preset. In the build_header function vector (which is gre_build_header for GRE, for example), the outer IP header would be constructed using these preset parameters:

static struct sk_buff *gre_build_header(const struct vport *vport,
                     const struct tnl_mutable_config *mutable,
                     struct dst_entry *dst,
                     struct sk_buff *skb,
                     int tunnel_hlen)
{
[snip]
    const struct ovs_key_ipv4_tunnel *tun_key = OVS_CB(skb)->tun_key;
[snip]
}


### ODP Implementation and Example Setting of Flow-based Tunneling

The IPv4 tunnel parameters for flow-based tunneling can be configured on Midolman via the FlowKeyTunnel class. This class definition has content that is the replica of the datapath kernel data structure ovs_key_ipv4_tunnel.

As enumerated by ovs_tunnel_key_attr, all the fields inside a tunnel key can be optionally set - that is, by having an attribute identifier for all the fields, OVS is effectively making every fields in tunnel key setting (fields specified above) optional. The FlowKeyTunnel class follows this conventional and gives user/applications freedom to set any fields within the data structure. Realistically, the kernel module would reject the SET command if the destination IP address is not set, or if the TTL field is zero

int ipv4_tun_from_nlattr(const struct nlattr *attr,
             struct ovs_key_ipv4_tunnel *tun_key)
{
    [snip]


    if (!tun_key->ipv4_dst) {
        return -EINVAL;
    }

    if (!ttl) {
        return -EINVAL;
    }
    [snip]
}

In addition, during a SET, the entire tunnel key structure got reinitialized, therefore, user program which wants to set just one particular field (say the TOS byte) should perform a GET operation first, then SET with all the other fields replicated from the data obtained from the previous GET

The following code snippet highlights how to set tunnel parameters in the control plane:

FlowKeyTunnel ipv4TunnelKey = new FlowKeyTunnel()
                                .setTunnelID(tunnelId)
                                .setIpv4SrcAddr(tunnelSrcIp)
                                .setIpv4DstAddr(tunnelDstIp)
                                .setTunnelFlags(tunnelFlag)
                                .setTos(tunnelDscp)
                                .setTtl(TUNNEL_DEFAULT_TTL);
FlowActionSetKey setKeyAction = new FlowActionSetKey()
                                .setFlowKey(ipv4TunnelKey);
actionBucketList.add(setKeyAction);

