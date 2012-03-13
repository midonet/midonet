/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.state;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.agent.command.CommandProperty;

/**
 * ZooKeeper state objects definitions for Host and Interface data.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/1/12
 */
public class HostDirectory {

    public static class ErrorLogItem {
        Integer commandId;
        String error;
        String intefaceName;
        Date time = Calendar.getInstance().getTime();

        public Integer getCommandId() {
            return commandId;
        }

        public void setCommandId(Integer commandId) {
            this.commandId = commandId;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getIntefaceName() {
            return intefaceName;
        }

        public void setIntefaceName(String intefaceName) {
            this.intefaceName = intefaceName;
        }

        public Date getTime() {
            return time;
        }

        public void setTime(Date time) {
            this.time = time;
        }
    }

    public static class Command {

        public static class AtomicCommand {

            public enum OperationType {
                // TODO(rossella) add other needed operation
                SET, DELETE, CLEAR
            }

            CommandProperty property;
            String value;
            OperationType OpType;

            // Default constructor for the Jackson de-serialization.
            public AtomicCommand() {
            }

            public String getValue() {
                return value;
            }

            public void setValue(Object value) {
                this.value = value.toString();
            }

            public OperationType getOpType() {
                return OpType;
            }

            public void setOpType(OperationType opType) {
                OpType = opType;
            }

            public CommandProperty getProperty() {
                return property;
            }

            public void setProperty(CommandProperty property) {
                this.property = property;
            }

            @Override
            public String toString() {
                return "AtomicCommand{" +
                    "property='" + property + '\'' +
                    ", value='" + value + '\'' +
                    ", OpType=" + OpType +
                    '}';
            }
        }

        public String interfaceName;

        List<AtomicCommand> commandList = new ArrayList<AtomicCommand>();

        // Default constructor for the Jackson de-serialization.
        public Command() {

        }

        public String getInterfaceName() {
            return interfaceName;
        }

        public void setInterfaceName(String interfaceName) {
            this.interfaceName = interfaceName;
        }

        public void addAtomicCommand(Command.AtomicCommand command)
        {
            commandList.add(command);
        }

        public List<AtomicCommand> getCommandList() {
            return commandList;
        }

        public void setCommandList(
                List<AtomicCommand> commandList) {
            this.commandList = commandList;
        }

        @Override
        public String toString() {
            return "Command{" +
                "interfaceName='" + interfaceName + '\'' +
                ", commandList=" + commandList +
                '}';
        }
    }

    /**
     * Metadata for a host description (contains a host name and a list of known addresses)
     */
    public static class Metadata {

        String name;
        InetAddress[] addresses;

        // Default constructor for the Jackson de-serialization.
        public Metadata() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public InetAddress[] getAddresses() {
            return addresses;
        }

        public void setAddresses(InetAddress[] addresses) {
            this.addresses = addresses;
        }
    }

    /**
     * A host interface description.
     */
    public static class Interface {

        public enum Type {
            Unknown, Physical, Virtual, Tunnel
        }

        public enum StatusType {
            Up(0x01), Carrier(0x02);

            private int mask;

            private StatusType(int mask) {
                this.mask = mask;
            }

            public int getMask() {
                return mask;
            }
        }

        UUID id;
        String name;
        Type type = Type.Unknown;
        String endpoint;
        byte[] mac;
        int status;
        int mtu;
        InetAddress[] addresses;
        Map<String, String> properties;

        public Interface() {
        }

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public byte[] getMac() {
            return mac;
        }

        public void setMac(byte[] mac) {
            this.mac = mac;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public int getMtu() {
            return mtu;
        }

        public void setMtu(int mtu) {
            this.mtu = mtu;
        }

        public InetAddress[] getAddresses() {
            return addresses;
        }

        public void setAddresses(InetAddress[] addresses) {
            this.addresses = addresses;
        }

        public boolean hasAddress(InetAddress searchAddress) {
            for (InetAddress address : addresses) {
                if (searchAddress.equals(address)) {
                    return true;
                }
            }
            return false;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Interface that = (Interface) o;

            if (mtu != that.mtu) return false;
            if (status != that.status) return false;
            if (!Arrays.equals(addresses, that.addresses)) return false;
            if (endpoint != null ? !endpoint.equals(
                that.endpoint) : that.endpoint != null) return false;
            if (id != null ? !id.equals(that.id) : that.id != null)
                return false;
            if (!Arrays.equals(mac, that.mac)) return false;
            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;
            if (properties != null ? !properties.equals(
                that.properties) : that.properties != null) return false;
            if (type != that.type) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            result = 31 * result + (type != null ? type.hashCode() : 0);
            result = 31 * result + (endpoint != null ? endpoint.hashCode() : 0);
            result = 31 * result + (mac != null ? Arrays.hashCode(mac) : 0);
            result = 31 * result + status;
            result = 31 * result + mtu;
            result = 31 * result + (addresses != null ? Arrays.hashCode(
                addresses) : 0);
            result = 31 * result + (properties != null ? properties.hashCode() : 0);
            return result;
        }

        // Copy constructor
        public Interface(Interface original) {

            this.mtu = original.mtu;
            this.status = original.status;
            this.addresses = original.addresses.clone();
            this.endpoint = original.endpoint;
            this.mac = original.mac.clone();
            this.name = new String(original.name);
            this.type = original.type;
            this.properties = new HashMap<String, String>(original.properties);
            // UUID is unique so we cannot copy it
        }
    }
}
