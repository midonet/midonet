/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.ports;

import java.util.UUID;

import com.midokura.midolman.util.Net;
import com.midokura.midonet.cluster.data.Port;
import com.midokura.midonet.cluster.data.Router;
import com.midokura.packets.MAC;

/**
 * Basic abstraction for a Router Port.
 */
public abstract class RouterPort<
    PortData extends RouterPort.Data,
    Self extends RouterPort<PortData, Self>
    > extends Port<PortData, Self> {

    protected RouterPort(Router router, UUID uuid, PortData portData){
        super(uuid, portData);
        if (getData() != null && router != null)
            setDeviceId(router.getId());
    }

    public String getNwAddr() {
        return Net.convertIntAddressToString(getData().nwAddr);
    }

    public Self setNwAddr(String nwAddr) {
        getData().nwAddr = Net.convertStringAddressToInt(nwAddr);
        return self();
    }

    public int getNwLength() {
        return getData().nwLength;
    }

    public Self setNwLength(int nwLength) {
        getData().nwLength = nwLength;
        return self();
    }

    public String getPortAddr() {
        return Net.convertIntAddressToString(getData().portAddr);
    }

    public Self setPortAddr(String portAddr) {
        getData().portAddr = Net.convertStringAddressToInt(portAddr);
        return self();
    }

    public MAC getHwAddr() {
        return getData().hwAddr;
    }

    public Self setHwAddr(MAC hwAddr) {
        getData().hwAddr = hwAddr;
        return self();
    }

    public static class Data extends Port.Data {
        public int nwAddr;
        public int nwLength;
        public int portAddr;
        public MAC hwAddr;

        @Override
        public boolean equals(Object o) {

            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;

            if (nwAddr != data.nwAddr) return false;
            if (nwLength != data.nwLength) return false;
            if (portAddr != data.portAddr) return false;
            if (hwAddr != null ? !hwAddr.equals(
                data.hwAddr) : data.hwAddr != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + nwAddr;
            result = 31 * result + nwLength;
            result = 31 * result + portAddr;
            result = 31 * result + (hwAddr != null ? hwAddr.hashCode() : 0);
            return result;
        }
    }

}

