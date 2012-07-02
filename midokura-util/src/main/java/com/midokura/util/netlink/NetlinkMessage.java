/*
* Copyright 2012 Midokura KK
*/
package com.midokura.util.netlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Stack;

public class NetlinkMessage {

    static final short NLA_F_NESTED = (short) (1 << 15);
    static final short NLA_F_NET_BYTEORDER = (1 << 14);
    static final short NLA_TYPE_MASK = ~NLA_F_NESTED | NLA_F_NET_BYTEORDER;

    ByteBuffer buf;
    Stack<Integer> starts = new Stack<Integer>();
    Stack<ByteBuffer> slices = new Stack<ByteBuffer>();

    public NetlinkMessage(int size) {
        buf = ByteBuffer.allocateDirect(size);
        buf.order(ByteOrder.nativeOrder());
    }

    public NetlinkMessage(ByteBuffer buf) {
        this.buf = buf;
    }

    public ByteBuffer getBuffer() {
        buf.flip();

        return buf;
    }

    public boolean hasRemaining() {
        return buf.hasRemaining();
    }

    public int putStringAttr(Netlink.ShortConstant type, String str) {
        return putStringAttr(type.getValue(), str);
    }

    public int putStringAttr(short type, String str) {
        int startPos = buf.position();

        int strLen = str.length() + 1;

        // put nl_attr for string
        buf.putShort((short) (4 + strLen)); // nla_len
        buf.putShort(type); // nla_type

        // put the string
        buf.put(str.getBytes());
        buf.put((byte) 0); // put a null terminator

        // pad
        int padLen = (int) (Math.ceil(strLen / 4.0) * 4) - strLen;
        for (int i = 0; i < padLen; i++) {
            buf.put((byte) 0);
        }

        return buf.position() - startPos;
    }

    public int put(short type, byte[] val) {
        int startPos = buf.position();

        // put nl_attr
        buf.putShort((short) (4 + val.length)); // nla_len
        buf.putShort(type); // nla_type

        // put the data
        buf.put(val);

        // pad
        int padLen = (int) (Math.ceil(val.length / 4.0) * 4) - val.length;
        for (int i = 0; i < padLen; i++) {
            buf.put((byte) 0);
        }

        return buf.position() - startPos;
    }

    public int putIntAttr(Netlink.ShortConstant type, int val) {
        return putIntAttr(type.getValue(), val);
    }

    int putIntAttr(short type, int val) {
        // put nl_attr for string
        buf.putShort((short) 8); // nla_len
        buf.putShort(type); // nla_type

        // put the value
        buf.putInt(val);

        return 8;
    }

    String getStringAttr(Netlink.ShortConstant type) {
        return getStringAttr(type.getValue());
    }

    String getStringAttr(short type) {
        short len = buf.getShort();
        short t = (short) (buf.getShort() & NLA_TYPE_MASK);
        if (t == type) {
            byte[] b = new byte[len - 4];
            buf.get(b);
            String res = new String(b, 0, len - 5);

            int pad = (int) (Math.ceil(len / 4.0) * 4) - len;
            buf.position(buf.position() + pad);

            return res;
        }

        int paddedLen = (int) (Math.ceil(len / 4.0) * 4) - len;
        buf.position(buf.position() + paddedLen - 4);

        return null;
    }

    public Integer getIntAttr(Netlink.ShortConstant type) {
        return getIntAttr(type.getValue());
    }

    Integer getIntAttr(short type) {
        short len = buf.getShort();
        short t = (short) (buf.getShort() & NLA_TYPE_MASK);
        if (t == type) {
            return buf.getInt();
        }

        int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
        buf.position(buf.position() + paddedLen - 4);

        return null;
    }

    int findIntAttr(short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    return buf.getInt();
                }

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
            }

            return -1;
        } finally {
            buf.reset();
        }
    }

    short findShortAttr(Netlink.ShortConstant type) {
        return findShortAttr(type.getValue());
    }

    short findShortAttr(short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    return buf.getShort();
                }

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
            }

            return -1;
        } finally {
            buf.reset();
        }
    }

    public String findStringAttr(Netlink.ShortConstant type) {
        return findStringAttr(type.getValue());
    }

    String findStringAttr(short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    byte[] b = new byte[len - 4];
                    buf.get(b);
                    return new String(b, 0, len - 5);
                }

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    public NetlinkMessage findNested(Netlink.ShortConstant type) {
        return findNested(type.getValue());
    }

    NetlinkMessage findNested(short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    int limit = buf.limit();

                    buf.limit(buf.position() + len - 4);

                    ByteBuffer slice = buf.slice();
                    slice.order(ByteOrder.nativeOrder());

                    buf.limit(limit);

                    return new NetlinkMessage(slice);
                }

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    void startNested(short type) {
        // save position
        starts.push(buf.position());

        // put a nl_attr header
        buf.putShort((short) 0);
        buf.putShort(type); // nla_type
    }

    void endNested() {
        int start = starts.pop();
        // update the nl_attr length
        buf.putShort(start, (short) (buf.position() - start));
    }
}

