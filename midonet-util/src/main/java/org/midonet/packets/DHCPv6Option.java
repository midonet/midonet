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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DHCPv6Option implements DHCPv6OptPacket {


    /*
     * all DHCP options have the following format:
     *
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |          option-code          |           option-len          |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                          option-data                          |
     * |                      (option-len octets)                      |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * The individual options then further define how the option-data is.
     */
    public static final Map<Short, String> codeToName = new HashMap<Short, String>();
    private static final int OPTION_CODE_LEN = 2;
    private static final int OPTION_LEN_LEN = 2;
    public static final short CLIENTID = 1;
    public static final short SERVERID = 2;
    public static final short IA_NA = 3;
    public static final short IA_TA = 4;
    public static final short IAADDR = 5;
    public static final short ORO = 6;
    public static final short PREFERENCE = 7;
    public static final short ELAPSED_TIME = 8;
    public static final short RELAY_MSG = 9;
    public static final short AUTH = 11;
    public static final short UNICAST = 12;
    public static final short STATUS_CODE = 13;
    public static final short RAPID_COMMIT = 14;
    public static final short USER_CLASS = 15;
    public static final short VENDOR_CLASS = 16;
    public static final short VENDOR_OPTS = 17;
    public static final short INTERFACE_ID = 18;
    public static final short RECONF_MSG = 19;
    public static final short RECONF_ACCEPT = 20;
    protected IPacket parent;
    protected IPacket payload;

    protected short code;
    protected short length;
    protected byte[] data;

    public DHCPv6Option() {}

    public IPacket getParent() {
        return parent;
    }
    public IPacket setParent(IPacket parent) {
        this.parent = parent;
        return this;
    }
    public IPacket getPayload() {
        return payload;
    }

    @Override
    public int getPayloadLength() {
        return 0;
    }

    public IPacket setPayload(IPacket payload) {
        this.payload = payload;
        return this;
    }

    public DHCPv6Option (short code, short length, byte[] data) {
        this.code = code;
        this.length = length;
        this.data = data;
    }

    public short getCode() {
        return code;
    }

    public short getLength() {
        return length;
    }

    public byte[] getData() {
        return data;
    }

    public void setCode(short code) {
        this.code = code;
    }

    public void setLength(short length) {
        this.length = length;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public static DHCPv6OptPacket getDHCPv6Option(int code) {
        DHCPv6Option option;
        switch (code) {
            case DHCPv6Option.CLIENTID:
                option = new DHCPv6Option.ClientId();
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
        return option;
    }

    /*
     * deserializeData is called by "deserialize". All of the DHCPv6
     * options have the same first 2 fields, which are code and len.
     * deserialize handles those, deserializeData handles all the rest
     * of the option specific data.
     * Why bother seperating them? because we need to know the code before
     * we know what type of option it is, and how to deserialize the rest
     * of the data.
     */
    public void deserializeData(ByteBuffer bb) {
        bb.get(data);
    }

    public IPacket deserialize(ByteBuffer bb) {
        this.code = bb.getShort();
        this.length = bb.getShort();
        this.deserializeData(bb);
        return this;
    }

    public byte[] serialize() {
        int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
        byte[] data = new byte[totalLen];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putShort(this.code);
        bb.putShort(this.length);
        bb.put(data);

        return data;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof DHCPv6Option)) {
            return false;
        }
        DHCPv6Option other = (DHCPv6Option) obj;
        if (code != other.code) {
            return false;
        }
        if (!Arrays.equals(data, other.data)) {
            return false;
        }
        if (length != other.length) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DHCPv6Option [code=").append(code);
        sb.append(", length=").append(length);
        sb.append(", data=").append(Arrays.toString(data));
        sb.append("]");
        return sb.toString();
    }

    /*
     * class representing the CLIENTID DHCPv6 Option
     */
    public static class ClientId extends DHCPv6Option {

        /*
         * The format of the CLIENTID option is:
         *
         *  0                   1                   2                   3
         *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         * |        OPTION_CLIENTID        |          option-len           |
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         * .                                                               .
         * .                              DUID                             .
         * .                        (variable length)                      .
         * .                                                               .
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *
         *
         */
        // variable length DUID field
        protected String DUID;

        public ClientId() {
            DUID = null;
        }

        public ClientId(String DUID) {
            this.code = CLIENTID;
            this.DUID = DUID;
            this.length = (short)getDUIDinBytes(DUID).length;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);

            bb.putShort(this.code);
            bb.putShort(this.length);

            if (DUID != null) {
                bb.put(getDUIDinBytes(this.DUID));
            }

            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            byte[] data = new byte[this.length];
            bb.get(data);
            this.DUID = getDUIDfromBytes(data);
        }

        public void setDUID(String duid) {
            this.DUID = duid;
            this.length = (short)getDUIDinBytes(DUID).length;
        }

        public String getDUID() {
            return this.DUID;
        }

        public static byte[] getDUIDinBytes(String duid) {
            char[] carr = duid.replace(":", "").toCharArray();
            byte[] data = new byte[carr.length/2];
            ByteBuffer bb = ByteBuffer.wrap(data);
            int i = 0;
            // dividing by 2 because carr is an array of nibbles, but we are dealing in bytes
            while (i < carr.length) {
                bb.put((byte)(((Integer.parseInt(String.valueOf(carr[i++]), 16) << 4) & 0xF0 |
                       Integer.parseInt(String.valueOf(carr[i++]), 16))));
            }
            return data;
        }

        public static String getDUIDfromBytes(byte[] duid) {
            StringBuilder str = new StringBuilder(Integer.toHexString((byte)((duid[0] >> 4) & 0x0F)));
            str.append(Integer.toHexString(((byte)(duid[0] & 0x0F))));
            int i = 1;
            while (i < duid.length) {
                str.append(":");
                str.append(Integer.toHexString((byte)((duid[i] >> 4) & 0x0F)));
                str.append(Integer.toHexString((byte)(duid[i++] & 0x0F)));
            }
            return str.toString();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ClientId [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", DUID=").append(DUID);
            return sb.toString();
        }
    }

    /*
     * class representing the SERVERID DHCPv6 Option
     */
    public static class ServerId extends DHCPv6Option {

        /*
         * The format of the SERVERID option is:
         *
         *  0                   1                   2                   3
         *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         * |        OPTION_SERVERID        |          option-len           |
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         * .                                                               .
         * .                              DUID                             .
         * .                        (variable length)                      .
         * .                                                               .
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *
         *
         */
        // variable length DUID field
        protected String DUID;

        public ServerId() {}

        public ServerId(String duid) {
            this.code = SERVERID;
            this.DUID = duid;
            this.length = (short)DHCPv6Option.ClientId.getDUIDinBytes(DUID).length;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);

            bb.putShort(this.code);
            bb.putShort(this.length);

            if (DUID != null) {
                bb.put(ClientId.getDUIDinBytes(this.DUID));
            }

            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            byte[] data = new byte[this.length];
            bb.get(data);
            this.DUID = ClientId.getDUIDfromBytes(data);
        }

        public void setDUID(String duid) {
            this.DUID = duid;
            this.length = (short)DHCPv6Option.ClientId.getDUIDinBytes(DUID).length;
        }

        public String getDUID() {
            return this.DUID;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ServerId [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", DUID=").append(DUID);
            return sb.toString();
        }
    }

    /*
     * class representing ORO option.
     */
    public static class Oro extends DHCPv6Option {

        /*
         * Format of the Option Request Option::
         *
         *   0                   1                   2                   3
         *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |           OPTION_ORO          |           option-len          |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |    requested-option-code-1    |    requested-option-code-2    |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                              ...                              |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *
         */

        protected List<Short> optCodes;

        public List<Short> getOptCodes() {
            return this.optCodes;
        }

        public void setOptCodes(List<Short> optCodes) {
            this.optCodes = optCodes;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);
            bb.putShort(this.code);
            bb.putShort(this.length);
            for (short c : optCodes) {
                bb.putShort(c);
            }
            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            this.optCodes = new ArrayList<Short>();
            int i = 0;
            while (i < this.length/2) {
                this.optCodes.add(bb.getShort());
                i++;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DHCPv6Option.ClientId [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", optCodes=[ ");
            for (Short c : optCodes) {
                sb.append(c).append(" ");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /*
     * class representing the ELAPSED_TIME option.
     */
    public static class ElapsedTime extends DHCPv6Option {

        /*
         * Format of the Elapsed Time option.
         *
         *   0                   1                   2                   3
         *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |      OPTION_ELAPSED_TIME      |           option-len          |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |          elapsed-time         |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *
         */
        protected short et;

        public short getElapsedTime() {
            return this.et;
        }

        public void setElapsedTime(short et) {
            this.et = et;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);

            bb.putShort(this.code);
            bb.putShort(this.length);
            bb.putShort(this.et);
            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            this.et = bb.getShort();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ElapsedTime [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", et=").append(et);
            sb.append("]");
            return sb.toString();
        }
    }

    /*
     * class representing the IANA option.
     */
    public static class IANA extends DHCPv6Option {
        private final int T1_DEFAULT = 0;
        private final int T2_DEFAULT = 0;

        /*
         * Format of the Identity Association Non-temporary Address option.
         *
         *   0                   1                   2                   3
         *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |          OPTION_IA_NA         |          option-len           |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                        IAID (4 octets)                        |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                              T1                               |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                              T2                               |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                                                               |
         *  .                         IA_NA-options                         .
         *  .                                                               .
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *
         */
        protected int IAID;
        protected int T1;
        protected int T2;
        protected List<DHCPv6OptPacket> IANAOpts = new ArrayList<DHCPv6OptPacket>();
        protected static final int NOOPTS_LEN = 12;

        public IANA() {

        }

        public IANA(int IAID) {
            this.code = IA_NA;
            this.IAID = IAID;
            this.T1 = T1_DEFAULT;
            this.T2 = T2_DEFAULT;
        }

        public int getIAID() {
            return IAID;
        }

        public int getT1() {
            return T1;
        }

        public int getT2() {
            return T2;
        }

        public List<DHCPv6OptPacket> getIANAOpts() {
            return this.IANAOpts;
        }

        public void setIAID(int IAID) {
            this.IAID = IAID;
        }

        public void setT1(int T1) {
            this.T1 = T1;
        }

        public void setT2(int T2) {
            this.T2 = T2;
        }

        public void setIANAOpts(List<DHCPv6OptPacket> opts) {
            this.IANAOpts = opts;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);
            bb.putShort(this.code);
            bb.putShort(this.length);
            bb.putInt(this.IAID);
            bb.putInt(this.T1);
            bb.putInt(this.T2);
            for (DHCPv6OptPacket o : IANAOpts) {
                bb.put(o.serialize());
            }
            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            this.IAID = bb.getInt();
            this.T1 = bb.getInt();
            this.T2 = bb.getInt();
            IANAOpts = new ArrayList<DHCPv6OptPacket>();
            int bytesLeft = this.length - NOOPTS_LEN;
            while (bytesLeft > 0) {
                short code = bb.getShort();
                short len = bb.getShort();
                DHCPv6OptPacket option = getDHCPv6Option(code);
                option.setCode(code);
                option.setLength(len);
                option.deserializeData(bb);
                this.IANAOpts.add(option);
                bytesLeft -= (OPTION_CODE_LEN + OPTION_LEN_LEN + len);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DHCPv6Option.IANA [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", IAID=").append(IAID);
            sb.append(", T1=").append(T1);
            sb.append(", T2=").append(T2);
            sb.append(", options={").append(T2);
            for (DHCPv6OptPacket o : IANAOpts) {
                sb.append(o.toString()).append(", ");
            }
            sb.append("}]");
            return sb.toString();
        }

        public void calculateLength() {
            this.length = NOOPTS_LEN;
            for (DHCPv6OptPacket o : IANAOpts) {
                this.length += o.getLength() + OPTION_CODE_LEN + OPTION_LEN_LEN;
            }
        }
    }

    /*
     *
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |          OPTION_IAADDR        |          option-len           |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                                                               |
     * |                         IPv6 address                          |
     * |                                                               |
     * |                                                               |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                      preferred-lifetime                       |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                        valid-lifetime                         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * .                                                               .
     * .                        IAaddr-options                         .
     * .                                                               .
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     */
    public static class IAAddr extends DHCPv6Option {
        private static final int PREF_LIFETIME = 375;
        private static final int VALID_LIFETIME = 600;
        private static final short HEADER_LEN = 4 + 4 + 16;

        private int preferredLifetime;
        private int validLifetime;
        private IPv6Addr addr;
        private List<DHCPv6OptPacket> options;

        public IAAddr() {}

        public IAAddr(IPv6Addr addr, List<DHCPv6OptPacket> options) {
            code = IAADDR;
            preferredLifetime = PREF_LIFETIME;
            validLifetime = VALID_LIFETIME;
            this.addr = IPv6Addr.fromString(addr.toString());
            this.options = options;
            this.length = HEADER_LEN;
            if (options != null) {
                for (DHCPv6OptPacket option : options) {
                    this.length += option.getLength();
                }
            }
        }

        public int getPreferredLifetime() {
            return preferredLifetime;
        }

        public void setPreferredLifetime(int plt) {
            this.preferredLifetime = plt;
        }

        public int getValidLifetime() {
            return validLifetime;
        }

        public void setValidLifetime(int vlt) {
            this.validLifetime = vlt;
        }

        public IPv6Addr getAddr() {
            return IPv6Addr.fromString(this.addr.toString());
        }

        public void setAddr(IPv6Addr addr) {
            this.addr = IPv6Addr.fromString(addr.toString());
        }

        public List<DHCPv6OptPacket> getOptions() {
            return options;
        }

        public void setOptions(List<DHCPv6OptPacket> options) {
            this.options = options;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);
            bb.putShort(this.code);
            bb.putShort(this.length);
            long uw = addr.upperWord();
            long lw = addr.lowerWord();
            bb.putInt((int)(uw >> 32));
            bb.putInt((int)(uw));
            bb.putInt((int)(lw >> 32));
            bb.putInt((int)(lw));
            bb.putInt(this.preferredLifetime);
            bb.putInt(this.validLifetime);
            //TODO: put in the addr
            if (options != null) {
                for (DHCPv6OptPacket o : options) {
                    bb.put(o.serialize());
                }
            }
            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            long uw = ((long)(bb.getInt()) << 32 |
                       (long)(bb.getInt()));
            long lw = ((long)(bb.getInt()) << 32 |
                       (long)(bb.getInt()));
            this.preferredLifetime = bb.getInt();
            this.validLifetime = bb.getInt();
            this.addr = new IPv6Addr(uw, lw);
            int bytesLeft = this.length - HEADER_LEN;
            while (bytesLeft > 0) {
                short code = bb.getShort();
                short len = bb.getShort();
                DHCPv6OptPacket option = getDHCPv6Option(code);
                option.setCode(code);
                option.setLength(len);
                this.options.add(option);
                bytesLeft -= (OPTION_CODE_LEN + OPTION_LEN_LEN + len);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DHCPv6Option.IAaddr [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", preferredLifetime=").append(preferredLifetime);
            sb.append(", validLifetime=").append(validLifetime);
            if (options != null) {
                for (DHCPv6OptPacket o : options) {
                    sb.append(o.toString()).append(", ");
                }
            }
            sb.append("}]");
            return sb.toString();
        }

        public void calculateLength() {
            this.length = HEADER_LEN;
            for (DHCPv6OptPacket o : this.options) {
                this.length += o.getLength() + OPTION_CODE_LEN + OPTION_LEN_LEN;
            }
        }
    }
}

