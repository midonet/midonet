package org.midonet.packets;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.util.ArrayList;

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

    /*
     * Children of DHCPv6Otion need to implement a more specific
     * deserializeData function. This is just the general one
     * used for unsupported option types.
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
        protected byte[] DUIDBytes;

        public ClientId() {
            DUIDBytes = null;
        }

        public byte[] serialize() {
            int totalLen = OPTION_CODE_LEN + OPTION_LEN_LEN + this.length;
            byte[] data = new byte[totalLen];
            ByteBuffer bb = ByteBuffer.wrap(data);

            bb.putShort(this.code);
            bb.putShort(this.length);

            if (DUIDBytes != null) {
                bb.put(DUIDBytes);
            }

            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            DUIDBytes = new byte[this.length];
            bb.get(DUIDBytes);
        }

        public void setDUID(byte[] duid) {
            this.DUIDBytes = duid;
        }

        public byte[] getDUID() {
            return this.DUIDBytes;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DHCPv6OptClientId [code=").append(code);
            sb.append(", length=").append(length);
            sb.append(", DUID=").append(Arrays.toString(DUIDBytes));
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

        protected ArrayList<Short> optCodes;

        public ArrayList<Short> getOptCodes() {
            return this.optCodes;
        }

        public void setOptCodes(ArrayList<Short> optCodes) {
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
        protected ArrayList<Byte> IANAOpts = new ArrayList<Byte>();
        protected static final int NOOPTS_LEN = 16;

        public int getIAID() {
            return IAID;
        }

        public int getT1() {
            return T1;
        }

        public int getT2() {
            return T2;
        }

        public ArrayList<Byte> getIANAOpts() {
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

        public void setIANAOpts(ArrayList<Byte> opts) {
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
            for (byte b : IANAOpts) {
                bb.put(b);
            }
            return data;
        }

        @Override
        public void deserializeData(ByteBuffer bb) {
            this.IAID = bb.getInt();
            this.T1 = bb.getInt();
            this.T2 = bb.getInt();
            IANAOpts = new ArrayList<Byte>();
            while (bb.hasRemaining()) {
                IANAOpts.add(bb.get());
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
            for (byte b : IANAOpts) {
                sb.append(b).append(" ");
            }
            sb.append("]");
            return sb.toString();
        }
    }
}

