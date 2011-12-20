package com.midokura.midolman.openvswitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MockOpenvSwitchDatabaseConnection implements
        OpenvSwitchDatabaseConnection {

    Logger log = LoggerFactory.getLogger(
                               MockOpenvSwitchDatabaseConnection.class);

    Map<Long, Map<Integer, Map<String, String>>> bridgeToExternalIds =
        new HashMap<Long, Map<Integer, Map<String, String>>>();

    static public class GrePort {
        public String bridgeName;
        public String portName;
        public String address;

        public GrePort(String a, String b, String c) {
            bridgeName = a;
            portName = b;
            address = c;
        }

        @Override
        public boolean equals(Object rhs) {
            if (rhs == null)
                return false;
            if (rhs == this)
                return true;
            if (!(rhs instanceof GrePort))
                return false;
            GrePort other = GrePort.class.cast(rhs);
            return bridgeName.equals(other.bridgeName)
                    && portName.equals(other.portName)
                    && address.equals(other.address);
        }
    }

    public ArrayList<GrePort> addedGrePorts = new ArrayList<GrePort>();
    public ArrayList<String> deletedPorts = new ArrayList<String>();

    @Override
    public BridgeBuilder addBridge(String name) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortBuilder addSystemPort(long bridgeId, String portName) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortBuilder addSystemPort(String bridgeName, String portName) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortBuilder addInternalPort(long bridgeId, String portName) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortBuilder addInternalPort(String bridgeName, String portName) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortBuilder addTapPort(long bridgeId, String portName) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortBuilder addTapPort(String bridgeName, String portName) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public GrePortBuilder addGrePort(
            final long bridgeId,
            final String portName,
            final String remoteIp) {
        
        return new GrePortBuilder() {
            
            @Override
            public GrePortBuilder ttlInherit() {
                return this;
            }
            
            @Override
            public GrePortBuilder ttl(byte ttl) {
                return this;
            }
            
            @Override
            public GrePortBuilder tosInherit() {
                return this;
            }
            
            @Override
            public GrePortBuilder tos(byte tos) {
                return this;
            }
            
            @Override
            public GrePortBuilder outKeyFlow() {
                return this;
            }
            
            @Override
            public GrePortBuilder outKey(int outKey) {
                return this;
            }
            
            @Override
            public GrePortBuilder localIp(String localIp) {
                return this;
            }
            
            @Override
            public GrePortBuilder keyFlow() {
                return this;
            }
            
            @Override
            public GrePortBuilder key(int key) {
                return this;
            }
            
            @Override
            public GrePortBuilder inKeyFlow() {
                return this;
            }
            
            @Override
            public GrePortBuilder inKey(int inKey) {
                return this;
            }
            
            @Override
            public GrePortBuilder ifMac(String ifMac) {
                return this;
            }
            
            @Override
            public GrePortBuilder externalId(String key, String value) {
                return this;
            }
            
            @Override
            public GrePortBuilder enableCsum() {
                return this;
            }
            
            @Override
            public GrePortBuilder disablePmtud() {
                return this;
            }
            
            @Override
            public GrePortBuilder disableHeaderCache() {
                return this;
            }
            
            @Override
            public void build() {
                addedGrePorts.add(
                        new GrePort(
                                Long.toString(bridgeId), portName, remoteIp));
            }
        };
    }

    @Override
    public GrePortBuilder addGrePort(
            final String bridgeName,
            final String portName,
            final String remoteIp) {
        
        return new GrePortBuilder() {
            
            @Override
            public GrePortBuilder ttlInherit() {
                return this;
            }
            
            @Override
            public GrePortBuilder ttl(byte ttl) {
                return this;
            }
            
            @Override
            public GrePortBuilder tosInherit() {
                return this;
            }
            
            @Override
            public GrePortBuilder tos(byte tos) {
                return this;
            }
            
            @Override
            public GrePortBuilder outKeyFlow() {
                return this;
            }
            
            @Override
            public GrePortBuilder outKey(int outKey) {
                return this;
            }
            
            @Override
            public GrePortBuilder localIp(String localIp) {
                return this;
            }
            
            @Override
            public GrePortBuilder keyFlow() {
                return this;
            }
            
            @Override
            public GrePortBuilder key(int key) {
                return this;
            }
            
            @Override
            public GrePortBuilder inKeyFlow() {
                return this;
            }
            
            @Override
            public GrePortBuilder inKey(int inKey) {
                return this;
            }
            
            @Override
            public GrePortBuilder ifMac(String ifMac) {
                return this;
            }
            
            @Override
            public GrePortBuilder externalId(String key, String value) {
                return this;
            }
            
            @Override
            public GrePortBuilder enableCsum() {
                return this;
            }
            
            @Override
            public GrePortBuilder disablePmtud() {
                return this;
            }
            
            @Override
            public GrePortBuilder disableHeaderCache() {
                return this;
            }
            
            @Override
            public void build() {
                addedGrePorts.add(new GrePort(bridgeName, portName, remoteIp));
            }
        };
    }

    @Override
    public void delPort(String portName) {
        deletedPorts.add(portName);
    }

    @Override
    public boolean hasPort(String portName) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ControllerBuilder addBridgeOpenflowController(long bridgeId,
            String target) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ControllerBuilder addBridgeOpenflowController(String bridgeName,
            String target) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void delBridgeOpenflowControllers(long bridgeId) {
        // TODO Auto-generated method stub

    }

    @Override
    public void delBridgeOpenflowControllers(String bridgeName) {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean hasBridge(long bridgeId) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean hasBridge(String bridgeName) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void delBridge(long bridgeId) {
        // TODO Auto-generated method stub

    }

    @Override
    public void delBridge(String bridgeName) {
        // TODO Auto-generated method stub

    }

    @Override
    public String getDatapathExternalId(long bridgeId, String externalIdKey) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getDatapathExternalId(String bridgeName, String externalIdKey) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getPortExternalId(String portName, String externalIdKey) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getPortExternalId(long bridgeId, int portNum,
                                    String externalIdKey) {
        log.info("reading external ID of dp#{} port#{} key:'{}'",
                 new Object[] { bridgeId, portNum, externalIdKey });
        Map<Integer, Map<String, String>> portToExternalIds =
                bridgeToExternalIds.get(bridgeId);
        if (null == portToExternalIds)
            return null;
        Map<String, String> externalIds = portToExternalIds.get(portNum);
        if (null == externalIds)
            return null;
        return externalIds.get(externalIdKey);
    }

    public void setPortExternalId(long bridgeId, int portNum,
                                  String externalIdKey, String value) {
        log.info("Setting external ID of dp#{} port#{} '{}' => '{}'",
                 new Object[] { bridgeId, portNum, externalIdKey, value });
        Map<Integer, Map<String, String>> portToExternalIds =
                bridgeToExternalIds.get(bridgeId);
        if (null == portToExternalIds) {
            portToExternalIds = new HashMap<Integer, Map<String, String>>();
            bridgeToExternalIds.put(bridgeId, portToExternalIds);
        }
        Map<String, String> externalIds = portToExternalIds.get(portNum);
        if (null == externalIds) {
            externalIds = new HashMap<String, String>();
            portToExternalIds.put(portNum, externalIds);
        }
        externalIds.put(externalIdKey, value);
    }

    @Override
    public String getPortExternalId(String bridgeName, int portNum,
            String externalIdKey) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public QosBuilder addQos(String type) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public QosBuilder updateQos(String qosUuid, String type) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void clearQosQueues(String qosUuid) {
        // TODO Auto-generated method stub

    }

    @Override
    public void delQos(String qosUuid) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setPortQos(String portName, String qosUuid) {
        // TODO Auto-generated method stub

    }

    @Override
    public void unsetPortQos(String portName) {
        // TODO Auto-generated method stub

    }

    @Override
    public QueueBuilder addQueue() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public QueueBuilder updateQueue(String queueUuid) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void delQueue(String queueUuid) {
        // TODO Auto-generated method stub

    }

    @Override
    public Set<String> getBridgeNamesByExternalId(String key, String value) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<String> getPortNamesByExternalId(String key, String value) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<Short> getPortNumsByPortName(String portName) {
        return null;  // TODO Auto-generated method stub
    }

    @Override
    public String getPortUUID(String portName) {
        return null;  // TODO Auto-generated method stub
    }

    @Override
    public short getPortNumByUUID(String portUuid) {
        return 0;  // TODO Auto-generated method stub
    }

    @Override
    public short getPortNumByInterfaceUUID(String ifUuid) {
        return 0;  // TODO Auto-generated method stub
    }

    @Override
    public int getQueueNumByQueueUUID(String qosUUID, String queueUUID) {
        return 0;  // TODO Auto-generated method stub
    }

    @Override
    public void close() {
        // TODO Auto-generated method stub
    }
}
