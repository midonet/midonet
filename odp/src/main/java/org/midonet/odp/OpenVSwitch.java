/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp;

/** This public interface contains integer constants defined in the openvswitch header.
 *  These constants can be maintenaned and kept updated by taking diffs of the
 *  openvswitch/include/linux/openvswitch.h header between different versions.
 *
 *  All classes and methods which needs to reference constant intues of the
 *  openvswitch netlink public interface for serialization and deserialization
 *  purposes should refer to this public interface exclusively.
 */
public interface OpenVSwitch {

    String headerVersion    = "1.11";

    public interface Datapath {

        //#define OVS_DATAPATH_FAMILY  "ovs_datapath"
        //#define OVS_DATAPATH_MCGROUP "ovs_datapath"
        //#define OVS_DATAPATH_VERSION 0x1
        String Family       = "ovs_datapath";
        String MCGroup      = "ovs_datapath";
        int version         = 0x01;

        //enum ovs_datapath_cmd {
        //    OVS_DP_CMD_UNSPEC,
        //    OVS_DP_CMD_NEW,
        //    OVS_DP_CMD_DEL,
        //    OVS_DP_CMD_GET,
        //    OVS_DP_CMD_SET
        //};
        public interface Cmd {
            int New         = 1;
            int Del         = 2;
            int Get         = 3;
            int Set         = 4;
        }

        //enum ovs_datapath_attr {
        //    OVS_DP_ATTR_UNSPEC,
        //    OVS_DP_ATTR_NAME,       /* name of dp_ifindex netdev */
        //    OVS_DP_ATTR_UPCALL_PID, /* Netlink PID to receive upcalls */
        //    OVS_DP_ATTR_STATS,      /* struct ovs_dp_stats */
        //    __OVS_DP_ATTR_MAX
        //};
        public interface Attr {
            int Name        = 1;
            int UpcallPID   = 2;
            int Stat        = 3;
        }

        //struct ovs_dp_stats {
        //    __u64 n_hit;             /* Number of flow table matches. */
        //    __u64 n_missed;          /* Number of flow table misses. */
        //    __u64 n_lost;            /* Number of misses not sent to userspace. */
        //    __u64 n_flows;           /* Number of flows present */
        //};

        //struct ovs_vport_stats {
        //	__u64   rx_packets;		/* total packets received       */
        //	__u64   tx_packets;		/* total packets transmitted    */
        //	__u64   rx_bytes;		/* total bytes received         */
        //	__u64   tx_bytes;		/* total bytes transmitted      */
        //	__u64   rx_errors;		/* bad packets received         */
        //	__u64   tx_errors;		/* packet transmit problems     */
        //	__u64   rx_dropped;		/* no space in linux buffers    */
        //	__u64   tx_dropped;		/* no space available in linux  */
        //};

    }

    public interface Packet {

        //#define OVS_PACKET_FAMILY "ovs_packet"
        //#define OVS_PACKET_VERSION 0x1
        String Family       = "ovs_packet";
        int version         = 0x01;

        //enum ovs_packet_cmd {
        //	OVS_PACKET_CMD_UNSPEC,
        //
        //	/* Kernel-to-user notifications. */
        //	OVS_PACKET_CMD_MISS,    /* Flow table miss. */
        //	OVS_PACKET_CMD_ACTION,  /* OVS_ACTION_ATTR_USERSPACE action. */
        //
        //	/* Userspace commands. */
        //	OVS_PACKET_CMD_EXECUTE  /* Apply actions to a packet. */
        //};
        public interface Cmd {
            int Miss        = 1;
            int Action      = 2;
            int Exec        = 3;
        }

        //enum ovs_packet_attr {
        //	OVS_PACKET_ATTR_UNSPEC,
        //	OVS_PACKET_ATTR_PACKET,      /* Packet data. */
        //	OVS_PACKET_ATTR_KEY,         /* Nested OVS_KEY_ATTR_* attributes. */
        //	OVS_PACKET_ATTR_ACTIONS,     /* Nested OVS_ACTION_ATTR_* attributes. */
        //	OVS_PACKET_ATTR_USERDATA,    /* OVS_ACTION_ATTR_USERSPACE arg. */
        //	__OVS_PACKET_ATTR_MAX
        //};
        public interface Attr {
            int Packet      = 1;
            int Key         = 2;
            int Actions     = 3;
            int Userdata    = 4;
        }

    }

    public interface Port {

        //#define OVS_VPORT_FAMILY  "ovs_vport"
        //#define OVS_VPORT_MCGROUP "ovs_vport"
        //#define OVS_VPORT_VERSION 0x1
        String Family       = "ovs_vport";
        String MCGroup      = "ovs_vport";
        int version         = 0x01;

        int fallbackMCGroup = 33;    // in include/openvswitch/datapath-compat.h

        //enum ovs_vport_cmd {
        //	OVS_VPORT_CMD_UNSPEC,
        //	OVS_VPORT_CMD_NEW,
        //	OVS_VPORT_CMD_DEL,
        //	OVS_VPORT_CMD_GET,
        //	OVS_VPORT_CMD_SET
        //};
        public interface Cmd {
            int New         = 1;
            int Del         = 2;
            int Get         = 3;
            int Set         = 4;
        }

        //enum ovs_vport_type {
        //	OVS_VPORT_TYPE_UNSPEC,
        //	OVS_VPORT_TYPE_NETDEV,   /* network device */
        //	OVS_VPORT_TYPE_INTERNAL, /* network device implemented by datapath */
        //	OVS_VPORT_TYPE_GRE,	 /* GRE tunnel. */
        //	OVS_VPORT_TYPE_VXLAN,    /* VXLAN tunnel */
        //	OVS_VPORT_TYPE_GRE64 = 104, /* GRE tunnel with 64-bit keys */
        //	OVS_VPORT_TYPE_LISP = 105,  /* LISP tunnel */
        //	__OVS_VPORT_TYPE_MAX
        //};
        public interface Type {
            int Netdev      = 1;
            int Internal    = 2;
            int Gre         = 3;
            int VXLan       = 4;
            int Gre64       = 104;
            int Lisp        = 105;
        }

        //enum ovs_vport_attr {
        //	OVS_VPORT_ATTR_UNSPEC,
        //	OVS_VPORT_ATTR_PORT_NO,	/* u32 port number within datapath */
        //	OVS_VPORT_ATTR_TYPE,	/* u32 OVS_VPORT_TYPE_* constant. */
        //	OVS_VPORT_ATTR_NAME,	/* string name, up to IFNAMSIZ bytes long */
        //	OVS_VPORT_ATTR_OPTIONS, /* nested attributes, varies by vport type */
        //	OVS_VPORT_ATTR_UPCALL_PID, /* u32 Netlink PID to receive upcalls */
        //	OVS_VPORT_ATTR_STATS,	/* struct ovs_vport_stats */
        //	__OVS_VPORT_ATTR_MAX
        //};
        public interface Attr {
            int PortNo      = 1;
            int Type        = 2;
            int Name        = 3;
            int Options     = 4;
            int Options_N   = Options | (1 << 15); // for deserialization
            int UpcallPID   = 5;
            int Stats       = 6;
        }

        //enum {
        //	OVS_TUNNEL_ATTR_UNSPEC,
        //	OVS_TUNNEL_ATTR_DST_PORT, /* 16-bit UDP port, used by L4 tunnels. */
        //	__OVS_TUNNEL_ATTR_MAX
        //};
        public interface VPortTunnelOptions {
            int DstPort     = 1;                  // u16
        }

    }

    public interface Flow {

        //#define OVS_FLOW_FAMILY  "ovs_flow"
        //#define OVS_FLOW_MCGROUP "ovs_flow"
        //#define OVS_FLOW_VERSION 0x1
        String Family       = "ovs_flow";
        String MCGroup      = "ovs_flow";
        int version         = 0x01;

        //enum ovs_flow_cmd {
        //	OVS_FLOW_CMD_UNSPEC,
        //	OVS_FLOW_CMD_NEW,
        //	OVS_FLOW_CMD_DEL,
        //	OVS_FLOW_CMD_GET,
        //	OVS_FLOW_CMD_SET
        //};
        public interface Cmd {
            int New         = 1;
            int Del         = 2;
            int Get         = 3;
            int Set         = 4;
        }

        //enum ovs_flow_attr {
        //    OVS_FLOW_ATTR_UNSPEC,
        //    OVS_FLOW_ATTR_KEY,       /* Sequence of OVS_KEY_ATTR_* attributes. */
        //    OVS_FLOW_ATTR_ACTIONS,   /* Nested OVS_ACTION_ATTR_* attributes. */
        //    OVS_FLOW_ATTR_STATS,     /* struct ovs_flow_stats. */
        //    OVS_FLOW_ATTR_TCP_FLAGS, /* 8-bit OR'd TCP flags. */
        //    OVS_FLOW_ATTR_USED,      /* u64 msecs last used in monotonic time. */
        //    OVS_FLOW_ATTR_CLEAR,     /* Flag to clear stats, tcp_flags, used. */
        //    OVS_FLOW_ATTR_MASK,      /* Sequence of OVS_KEY_ATTR_* attributes. */
        //    __OVS_FLOW_ATTR_MAX
        //};
        public interface Attr {
            int Key         = 1;
            int Actions     = 2;
            int Stats       = 3;
            int TCPFlags    = 4;
            int Used        = 5;
            int Clear       = 6;
            int Mask        = 7;
        }

        //struct ovs_flow_stats {
        //	__u64 n_packets;         /* Number of matched packets. */
        //	__u64 n_bytes;           /* Number of matched bytes. */
        //};

    }

    public interface FlowKey {

        //enum ovs_key_attr {
        //	OVS_KEY_ATTR_UNSPEC,
        //	OVS_KEY_ATTR_ENCAP,	/* Nested set of encapsulated attributes. */
        //	OVS_KEY_ATTR_PRIORITY,  /* u32 skb->priority */
        //	OVS_KEY_ATTR_IN_PORT,   /* u32 OVS dp port number */
        //	OVS_KEY_ATTR_ETHERNET,  /* struct ovs_key_ethernet */
        //	OVS_KEY_ATTR_VLAN,	/* be16 VLAN TCI */
        //	OVS_KEY_ATTR_ETHERTYPE,	/* be16 Ethernet type */
        //	OVS_KEY_ATTR_IPV4,      /* struct ovs_key_ipv4 */
        //	OVS_KEY_ATTR_IPV6,      /* struct ovs_key_ipv6 */
        //	OVS_KEY_ATTR_TCP,       /* struct ovs_key_tcp */
        //	OVS_KEY_ATTR_UDP,       /* struct ovs_key_udp */
        //	OVS_KEY_ATTR_ICMP,      /* struct ovs_key_icmp */
        //	OVS_KEY_ATTR_ICMPV6,    /* struct ovs_key_icmpv6 */
        //	OVS_KEY_ATTR_ARP,       /* struct ovs_key_arp */
        //	OVS_KEY_ATTR_ND,        /* struct ovs_key_nd */
        //	OVS_KEY_ATTR_SKB_MARK,  /* u32 skb mark */
        //	OVS_KEY_ATTR_TUNNEL,	/* Nested set of ovs_tunnel attributes */

        //#ifdef __KERNEL__
        //	OVS_KEY_ATTR_IPV4_TUNNEL,  /* struct ovs_key_ipv4_tunnel */
        //#endif

        //	OVS_KEY_ATTR_MPLS = 62, /* struct ovs_key_mpls */
        //	__OVS_KEY_ATTR_MAX
        //};
        public interface Attr {
            int Encap       = 1;
            int Priority    = 2;
            int InPort      = 3;
            int Ethernet    = 4;
            int VLan        = 5;
            int Ethertype   = 6;
            int IPv4        = 7;
            int IPv6        = 8;
            int TCP         = 9;
            int UDP         = 10;
            int ICMP        = 11;
            int ICMPv6      = 12;
            int ARP         = 13;
            int ND          = 14;
            int SkbMark     = 15;
            int Tunnel      = 16;
            int Tunnel_N    = (1 << 15) | Tunnel;
            int IPv4Tunnel  = 17;
            int MPLS        = 62;
        }

        //enum ovs_tunnel_key_attr {
        //    OVS_TUNNEL_KEY_ATTR_ID,			/* be64 Tunnel ID */
        //    OVS_TUNNEL_KEY_ATTR_IPV4_SRC,		/* be32 src IP address. */
        //    OVS_TUNNEL_KEY_ATTR_IPV4_DST,		/* be32 dst IP address. */
        //    OVS_TUNNEL_KEY_ATTR_TOS,		/* u8 Tunnel IP ToS. */
        //    OVS_TUNNEL_KEY_ATTR_TTL,		/* u8 Tunnel IP TTL. */
        //    OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT,	/* No argument, set DF. */
        //    OVS_TUNNEL_KEY_ATTR_CSUM,		/* No argument. CSUM packet. */
        //    __OVS_TUNNEL_KEY_ATTR_MAX
        //};
        public interface TunnelAttr {
            int Id          = 0;
            int IPv4Src     = 1;
            int IPv4Dst     = 2;
            int TOS         = 3;
            int TTL         = 4;
            int DontFrag    = 5;
            int CSum        = 6;
        }

        //enum ovs_frag_type {
        //    OVS_FRAG_TYPE_NONE,
        //    OVS_FRAG_TYPE_FIRST,
        //    OVS_FRAG_TYPE_LATER,
        //    __OVS_FRAG_TYPE_MAX
        //};
        public interface FragType {
            int None        = 0;
            int First       = 1;
            int Later       = 2;
        }

        //struct ovs_key_ethernet {
        //    __u8	 eth_src[ETH_ALEN];
        //    __u8	 eth_dst[ETH_ALEN];
        //};

        //struct ovs_key_mpls {
        //    __be32 mpls_top_lse;
        //};

        //struct ovs_key_ipv4 {
        //    __be32 ipv4_src;
        //    __be32 ipv4_dst;
        //    __u8   ipv4_proto;
        //    __u8   ipv4_tos;
        //    __u8   ipv4_ttl;
        //    __u8   ipv4_frag;	/* One of OVS_FRAG_TYPE_*. */
        //};

        //struct ovs_key_ipv6 {
        //    __be32 ipv6_src[4];
        //    __be32 ipv6_dst[4];
        //    __be32 ipv6_label;	/* 20-bits in least-significant bits. */
        //    __u8   ipv6_proto;
        //    __u8   ipv6_tclass;
        //    __u8   ipv6_hlimit;
        //    __u8   ipv6_frag;	/* One of OVS_FRAG_TYPE_*. */
        //};

        //struct ovs_key_tcp {
        //    __be16 tcp_src;
        //    __be16 tcp_dst;
        //};

        //struct ovs_key_udp {
        //    __be16 udp_src;
        //    __be16 udp_dst;
        //};

        //struct ovs_key_icmp {
        //    __u8 icmp_type;
        //    __u8 icmp_code;
        //};

        //struct ovs_key_icmpv6 {
        //    __u8 icmpv6_type;
        //    __u8 icmpv6_code;
        //};

        //struct ovs_key_arp {
        //    __be32 arp_sip;
        //    __be32 arp_tip;
        //    __be16 arp_op;
        //    __u8   arp_sha[ETH_ALEN];
        //    __u8   arp_tha[ETH_ALEN];
        //};

        //struct ovs_key_nd {
        //    __u32 nd_target[4];
        //    __u8  nd_sll[ETH_ALEN];
        //    __u8  nd_tll[ETH_ALEN];
        //};
    }

    public interface FlowAction {

        //enum ovs_action_attr {
        //    OVS_ACTION_ATTR_UNSPEC,
        //    OVS_ACTION_ATTR_OUTPUT,	      /* u32 port number. */
        //    OVS_ACTION_ATTR_USERSPACE,    /* Nested OVS_USERSPACE_ATTR_*. */
        //    OVS_ACTION_ATTR_SET,          /* One nested OVS_KEY_ATTR_*. */
        //    OVS_ACTION_ATTR_PUSH_VLAN,    /* struct ovs_action_push_vlan. */
        //    OVS_ACTION_ATTR_POP_VLAN,     /* No argument. */
        //    OVS_ACTION_ATTR_SAMPLE,       /* Nested OVS_SAMPLE_ATTR_*. */
        //    OVS_ACTION_ATTR_PUSH_MPLS,    /* struct ovs_action_push_mpls. */
        //    OVS_ACTION_ATTR_POP_MPLS,     /* __be16 ethertype. */
        //    __OVS_ACTION_ATTR_MAX
        //};
        public interface Attr {
            int Output      = 1;
            int Userspace   = 2;
            int Set         = 3;
            int PushVLan    = 4;
            int PopVLan     = 5;
            int Sample      = 6;
            int PushMPLS    = 7;
            int PopMPLS     = 8;
        }

        //enum ovs_sample_attr {
        //    OVS_SAMPLE_ATTR_UNSPEC,
        //    OVS_SAMPLE_ATTR_PROBABILITY, /* u32 number */
        //    OVS_SAMPLE_ATTR_ACTIONS,     /* Nested OVS_ACTION_ATTR_* attributes. */
        //    __OVS_SAMPLE_ATTR_MAX,
        //};
        public interface SampleAttr {
            int Probability = 1;
            int Actions     = 2;
        }

        //enum ovs_userspace_attr {
        //    OVS_USERSPACE_ATTR_UNSPEC,
        //    OVS_USERSPACE_ATTR_PID,	    /* u32 Netlink PID to receive upcalls. */
        //    OVS_USERSPACE_ATTR_USERDATA,  /* Optional user-specified cookie. */
        //    __OVS_USERSPACE_ATTR_MAX
        //};
        public interface UserspaceAttr {
            int PID         = 1;
            int Userdata    = 2;
        }

        //struct ovs_action_push_mpls {
        //    __be32 mpls_lse;
        //    __be16 mpls_ethertype; /* Either %ETH_P_MPLS_UC or %ETH_P_MPLS_MC */
        //};

        //struct ovs_action_push_vlan {
        //    __be16 vlan_tpid;	/* 802.1Q TPID. */
        //    __be16 vlan_tci;	/* 802.1Q TCI (VLAN ID and priority). */
        //};
    }

}
