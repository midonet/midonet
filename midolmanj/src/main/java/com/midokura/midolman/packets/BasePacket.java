package com.midokura.midolman.packets;

/**
*
* @author David Erickson (daviderickson@cs.stanford.edu)
*/
public abstract class BasePacket implements IPacket {
    protected IPacket parent;
    protected IPacket payload;

    /**
     * @return the parent
     */
    public IPacket getParent() {
        return parent;
    }

    /**
     * @param parent the parent to set
     */
    public IPacket setParent(IPacket parent) {
        this.parent = parent;
        return this;
    }

    /**
     * @return the payload
     */
    public IPacket getPayload() {
        return payload;
    }

    /**
     * @param payload the payload to set
     */
    public IPacket setPayload(IPacket payload) {
        this.payload = payload;
        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 6733;
        int result = 1;
        result = prime * result + ((payload == null) ? 0 : payload.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof BasePacket))
            return false;
        BasePacket other = (BasePacket) obj;
        if (payload == null) {
            if (other.payload != null)
                return false;
        } else if (!payload.equals(other.payload))
            return false;
        return true;
    }
}
