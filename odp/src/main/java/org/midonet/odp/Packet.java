/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.family.PacketFamily;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.packets.Ethernet;

/**
 * An abstraction over the Ovs kernel datapath Packet entity. Contains an
 * {@link FlowMatch} object and a <code>byte[] data</code> member when triggered
 * via a kernel notification.
 *
 * @see FlowMatch
 * @see OvsDatapathConnection#packetsExecute(Datapath, Packet)
 * @see OvsDatapathConnection#datapathsSetNotificationHandler(Datapath, Callback)
 */
public class Packet {

    public enum Reason {
        FlowTableMiss,
        FlowActionUserspace,
    }

    private FlowMatch match = new FlowMatch();
    private Long userData;
    private Reason reason;
    private Ethernet eth;
    private AtomicBoolean simToken = new AtomicBoolean(false);
    private long startTimeNanos = 0;

    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    public Packet setStartTimeNanos(long time) {
        this.startTimeNanos = time;
        return this;
    }

    public Ethernet getPacket() {
        return eth;
    }

    public Packet setPacket(Ethernet pkt) {
        this.eth = pkt;
        return this;
    }

    public byte[] getData() {
        return eth.serialize();
    }

    public FlowMatch getMatch() {
        return match;
    }

    public Packet setMatch(FlowMatch match) {
        assert match != null;
        this.match = match;
        return this;
    }

    public Packet addKey(FlowKey key) {
        match.addKey(key);
        return this;
    }

    public Long getUserData() {
        return userData;
    }

    public Packet setUserData(Long userData) {
        this.userData = userData;
        return this;
    }

    public Reason getReason() {
        return reason;
    }

    public Packet setReason(Reason reason) {
        this.reason = reason;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Packet packet = (Packet) o;

        if (eth != null ? !eth.equals(packet.eth) : packet.eth != null)
            return false;
        if (match != null ? !match.equals(packet.match) : packet.match != null)
            return false;
        if (reason != packet.reason) return false;
        if (userData != null ? !userData.equals(
            packet.userData) : packet.userData != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = eth != null ? eth.hashCode() : 0;
        result = 31 * result + (match != null ? match.hashCode() : 0);
        result = 31 * result + (userData != null ? userData.hashCode() : 0);
        result = 31 * result + (reason != null ? reason.hashCode() : 0);
        return result;
    }


    @Override
    public String toString() {
        return "Packet{" +
            "data=" + eth +
            ", match=" + match +
            ", userData=" + userData +
            ", reason=" + reason +
            '}';
    }

    public static Packet fromEthernet(Ethernet eth) {
        return new Packet().setPacket(eth);
    }

    public static Packet buildFrom(ByteBuffer buffer) {
        Packet packet = new Packet();
        NetlinkMessage msg = new NetlinkMessage(buffer);

        int datapathIndex = msg.getInt(); // ignored

        packet.eth = msg.getAttrValueEthernet(PacketFamily.AttrKey.PACKET);
        if (packet.eth == null)
            return null;

        packet.match = new FlowMatch(
            msg.getAttrValue(PacketFamily.AttrKey.KEY, FlowKey.Builder));
        packet.userData = msg.getAttrValueLong(PacketFamily.AttrKey.USERDATA);

        return packet;
    }

    /** Prepares an ovs request for executing and a packet with the given list
     *  of actions. */
    public static ByteBuffer execRequest(ByteBuffer buf, int datapathId,
                                         Iterable<FlowKey> keys,
                                         Iterable<FlowAction> actions,
                                         Ethernet packet) {
        buf.putInt(datapathId);
// todo: find out about this !
        // TODO(pino): find out why ovs_packet_cmd_execute throws an
        // EINVAL if we put the PACKET attribute right after the
        // datapathId. I examined the ByteBuffers constructed with that
        // ordering of attributes and compared it to this one, and found
        // only the expected difference.

        short keyId = PacketFamily.AttrKey.KEY.getId();
        NetlinkMessage.writeAttrSeq(buf, keyId, keys, FlowKey.translator);

        short actId = PacketFamily.AttrKey.ACTIONS.getId();
        NetlinkMessage.writeAttrSeq(buf, actId, actions, FlowAction.translator);

        short pktId = PacketFamily.AttrKey.PACKET.getId();
        NetlinkMessage.writeRawAttribute(buf, pktId, packet.serialize());

        buf.flip();
        return buf;
    }
}
