package com.midokura.midolman.openvswitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MockOpenvSwitchDatabaseConnection implements
        OpenvSwitchDatabaseConnection
{
    Map<Long, Map<Integer, Map<String, String>>> bridgeToExternalIds =
        new HashMap<Long, Map<Integer, Map<String, String>>>();
    static public class GrePort {
	String bridgeName;
	String portName;
	String address;
	public GrePort(String a, String b, String c) {
	    bridgeName = a;
	    portName = b;
	    address = c;
	}

	public boolean equals(GrePort rhs) {
	    return bridgeName.equals(rhs.bridgeName) &&
		   portName.equals(rhs.portName) &&
		   address.equals(rhs.address);
	}
    }
    public ArrayList <GrePort> addedGrePorts = new ArrayList <GrePort>();
    public ArrayList <String> deletedPorts = new ArrayList <String>();

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
    public GrePortBuilder addGrePort(long bridgeId, String portName,
            String remoteIp) {
	addedGrePorts.add(new GrePort(Long.toString(bridgeId), portName, 
			              remoteIp));
        return null;
    }

    @Override
    public GrePortBuilder addGrePort(String bridgeName, String portName,
            String remoteIp) {
	addedGrePorts.add(new GrePort(bridgeName, portName, remoteIp));
	return null;
    }

    @Override
    public void delPort(String portName) {
        deletedPorts.add(portName);
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
    public void close() {
        // TODO Auto-generated method stub
    }
}
