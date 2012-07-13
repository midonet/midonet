/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink;

/**
 * Netlink API interface.
 */
public interface Netlink {

    public enum Protocol {
        NETLINK_ROUTE(0), NETLINK_W1(1), NETLINK_USERSOCK(2),
        NETLINK_FIREWALL(3), NETLINK_INET_DIAG(4), NETLINK_NFLOG(5),
        NETLINK_XFRM(6), NETLINK_SELINUX(7), NETLINK_ISCSI(8),
        NETLINK_AUDIT(9), NETLINK_FIB_LOOKUP(10), NETLINK_CONNECTOR(11),
        NETLINK_NETFILTER(12), NETLINK_IP6_FW(13), NETLINK_DNRTMSG(14),
        NETLINK_KOBJECT_UEVENT(15), NETLINK_GENERIC(16),
        NETLINK_SCSITRANSPORT(18), NETLINK_ECRYPTFS(19),
        NETLINK_RDMA(20), NETLINK_CRYPTO(21);

        int value;

        private Protocol(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum Flag {
        // Must be set on all request messages.
        NLM_F_REQUEST(0x001),
        // The message is part of a multipart msg
        // terminated by NLMSG_DONE.
        NLM_F_MULTI(0x002),
        // Request for an acknowledgment on success.
        NLM_F_ACK(0x004),
        // Echo this request.
        NLM_F_ECHO(0x008),

        // for READ
        // Return the complete table instead of a single entry.
        NLM_F_ROOT(0x100),
        // Return all entries matching criteria passed
        // in message content.	Not implemented yet.
        NLM_F_MATCH(0x200),
        // Return an atomic snapshot of the table.
        NLM_F_ATOMIC(0x400),
        //  Convenience macro;
        NLM_F_DUMP(0x200 | 0x400),

        // for NEW
        // Replace existing matching object.
        NLM_F_REPLACE(0x100),
        // Don't replace if already exists.
        NLM_F_EXCL(0x200),
        // Create object if it doesn't already exist.
        NLM_F_CREATE(0x400),
        // Add to the end of the object list.
        NLM_F_APPEND(0x800),;

        private short value;

        Flag(int value) {
            this.value = (short) value;
        }

        public short getValue() {
            return value;
        }

        public static short or(Netlink.Flag... flags) {
            int value = 0;
            for (Netlink.Flag flag : flags) {
                value |= flag.getValue();
            }

            return (short) value;
        }
    }

    public enum MessageType {
        NLMSG_NOOP(0x0001),
        NLMSG_ERROR(0x0002),
        NLMSG_DONE(0x0003),
        NLMSG_OVERRUN(0x0004),

        NLMSG_CUSTOM(Short.MAX_VALUE);


        short value;

        MessageType(int value) {
            this.value = (short) value;
        }

        public static MessageType findById(short type) {
            switch (type) {
                case 1:
                    return NLMSG_NOOP;
                case 2:
                    return NLMSG_ERROR;
                case 3:
                    return NLMSG_DONE;
                case 4:
                    return NLMSG_OVERRUN;
                default:
                    return NLMSG_CUSTOM;
            }
        }
    }


    /**
     * Abstracts a netlink address.
     */
    public class Address {

        private int pid;

        public Address(int pid) {
            this.pid = pid;
        }

        public int getPid() {
            return pid;
        }
    }

    public static class CommandFamily<
        Cmd extends Enum<Cmd> & ByteConstant,
        AttrKey extends NetlinkMessage.AttrKey>
    {
        private short familyId;
        private byte version;

        public CommandFamily(int familyId, int version) {
            this.version = (byte)version;
            this.familyId = (short) familyId;
        }

        public short getFamilyId() {
            return familyId;
        }

        public byte getVersion() {
            return version;
        }
    }

    public interface ByteConstant {
        byte getValue();
    }

    public interface ShortConstant {
        short getValue();
    }
}
