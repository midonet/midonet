/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.packets;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/*
 * Packet for DHCPv6. Completely different format of the packet from DHCPv4
 * means we need an entirely new class.
 *
 * The msg header for DHCPv6 is a lot simpler than for DHCPv4 because
 * all of the option data is in the generalized "option" payload.
 * This means less work in DHCPv6, but more work in DHCPv6Option.
 */
public class DHCPv6 extends BasePacket {

    /*
     * lengths of fields.
     */
    private static final int MSG_TYPE_LEN = 1;
    private static final int TRANSACTION_ID_LEN = 3;
    private static final int OPTION_CODE_LEN = 2;
    private static final int OPTION_LEN_LEN = 2;

    /*
     * msg types
     */
    public static final byte MSGTYPE_SOLICIT = 1;
    public static final byte MSGTYPE_ADVERTISE = 2;
    public static final byte MSGTYPE_REQUEST = 3;
    public static final byte MSGTYPE_REPLY = 7;

    /*
     * Structure of a DHCPv6 packet (RFC 3315)
     *
     *   0                   1                   2                   3
     *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *  |    msg-type   |               transaction-id                  |
     *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *  |                                                               |
     *  .                            options                            .
     *  .                           (variable)                          .
     *  |                                                               |
     *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     */
    public static final Map<Byte, String> typeName = new HashMap<Byte, String>();

    protected byte msgType;
    protected int transactionId;
    protected ArrayList<DHCPv6OptPacket> options = new ArrayList<DHCPv6OptPacket>();

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DHCPv6 [msgType=").append(msgType);
        sb.append(", transactionId=").append(transactionId);
        sb.append(", options=[");
        for (DHCPv6OptPacket d : options) {
            sb.append(d.toString()).append(" ");
        }
        sb.append("]");
        return sb.toString();
    }

    public byte getMsgType() {
        return msgType;
    }

    public int getTransactionId() {
        return transactionId;
    }

    public List<DHCPv6OptPacket> getOptions() {
        return options;
    }

    public void setMsgType(byte msgType) {
        this.msgType = msgType;
    }

    public void setTransactionId(int transactionId) {
        this.transactionId = transactionId;
    }

    public void setOptions(ArrayList<DHCPv6OptPacket> options) {
        this.options = options;
    }

    /*
     * get the Interface Address ID for this DHCPv6 packet.
     * return -1 to signify
     */
    public int getIAID() {
        for (DHCPv6OptPacket o : options) {
            if (o instanceof DHCPv6Option.IANA) {
                DHCPv6Option.IANA iana = (DHCPv6Option.IANA)o;
                return iana.getIAID();
            }
        }
        return -1;
    }

    /*
     * the Client ID of the requesting client for this DHCPv6 packet.
     */
    public String getClientId() {
        for (DHCPv6OptPacket o : options) {
            if (o instanceof DHCPv6Option.ClientId) {
                DHCPv6Option.ClientId clid = (DHCPv6Option.ClientId)o;
                return clid.getDUID();
            }
        }
        return null;
    }

    @Override
    public byte[] serialize() {

        // start with the header length, then add the length of each option.
        int totalLen = MSG_TYPE_LEN + TRANSACTION_ID_LEN;

        for (DHCPv6OptPacket option : this.options) {
            totalLen += OPTION_CODE_LEN;
            totalLen += OPTION_LEN_LEN;
            totalLen += (int)option.getLength();
        }

        byte[] data = new byte[totalLen];

        /*
         * put the header in first. Most of the content of a DHCPv6 packet
         * is in the options.
         */
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(this.msgType);
        // TransactionId is 3 bytes. Awkward.
        bb.put((byte) ((byte) 0xff & (this.transactionId >> 16)));
        bb.put((byte) ((byte) 0xff & (this.transactionId >> 8)));
        bb.put((byte) ((byte) 0xff & (this.transactionId)));

        /*
         * put each option in.
         */
        for (DHCPv6OptPacket option : this.options) {
            bb.put(option.serialize());
        }
        return data;
    }

    /*
     * Equal if the header is the same and all options are the same.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj == null) {
            return false;
        } else if (!(obj instanceof DHCPv6)) {
            return false;
        }
        DHCPv6 other = (DHCPv6) obj;
        if (this.msgType != other.getMsgType()) {
            return false;
        }
        if (this.transactionId != other.getTransactionId()) {
            return false;
        }
        if (this.options.size() != other.getOptions().size()) {
            return false;
        }
        /*
         * TODO: implement equals functions for each of the individual options
         *
         * for (int i = 0; i < this.options.size(); i++) {
         *     if (!this.options.get(i).equals(other.getOptions().get(i))) {
         *         return false;
         *     }
         * }
         */

        return true;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) {
        byte[] tmpBuf = new byte[3];
        this.msgType = bb.get();
        bb.get(tmpBuf);
        this.transactionId = ((tmpBuf[0] & 0xFF) << 16)
                           | ((tmpBuf[1] & 0xFF) << 8)
                           | (tmpBuf[2] & 0xFF);

        // read options
        while (bb.hasRemaining()) {
            DHCPv6OptPacket option;
            short code = bb.getShort();
            short len = bb.getShort();

            // This switch statement will grow as we support more options.
            switch (code) {
                case DHCPv6Option.CLIENTID:
                    option = new DHCPv6Option.ClientId();
                    break;
                case DHCPv6Option.SERVERID:
                    option = new DHCPv6Option.ServerId();
                    break;
                case DHCPv6Option.ELAPSED_TIME:
                    option = new DHCPv6Option.ElapsedTime();
                    break;
                case DHCPv6Option.ORO:
                    option = new DHCPv6Option.Oro();
                    break;
                case DHCPv6Option.IA_NA:
                    option = new DHCPv6Option.IANA();
                    break;
                case DHCPv6Option.IAADDR:
                    option = new DHCPv6Option.IAAddr();
                    break;
                default:
                    option = new DHCPv6Option();
                    break;
            }
            option.setCode(code);
            option.setLength(len);

            // We already got the code and len, so we only need to
            // deserialize the data.
            option.deserializeData(bb);
            this.options.add(option);
        }

        return this;
    }
}
