/*
 * @(#)Port        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class representing port.
 * 
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
@XmlRootElement
public class Port {

    public static final String LogicalRouterPort = "LogicalRouterPort";
    public static final String MaterializedRouterPort = 
        "MaterializedRouterPort";
    public static final String BridgePort = "BridgePort";
    
    private UUID id = null;
    private UUID deviceId = null;
    private String localNetworkAddress = null;
    private int localNetworkLength;
    private UUID peerId = null;
    private String networkAddress = null;
    private int networkLength;
    private String portAddress = null;
    private String type = null;
    
    /**
     * Get port ID.
     * 
     * @return  port ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set port ID.
     * 
     * @param  id  ID of the port.
     */
    public void setId(UUID id) {
        this.id = id;
    }
 
    /**
     * Get device ID.
     * 
     * @return  device ID.
     */
    public UUID getDeviceId() {
        return deviceId;
    }

    /**logical
     * Set device ID.
     * 
     * @param  id  ID of the device.
     */
    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * Get port local network address.
     * 
     * @return  network address
     */
    public String getLocalNetworkAddress () {
        return localNetworkAddress;
    }
    
    /**
     * Set port local network address.
     * 
     * @param  localNetworkAddress  network address
     */
    public void setLocalNetworkAddress(String localNetworkAddress) {
        this.localNetworkAddress = localNetworkAddress;
    }
    
    /**
     * Get port local network length.
     * 
     * @return  network length
     */
    public int getLocalNetworkLength () {
        return localNetworkLength;
    }
    
    /**
     * Set port local network length.
     * 
     * @param  localNetworkLength  network length
     */
    public void setLocalNetworkLength(int localNetworkLength) {
        this.localNetworkLength = localNetworkLength;
    }
    
    /**
     * Get peer port ID.
     * 
     * @return  peer port ID.
     */
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * Set peer port ID.
     * 
     * @param  id  ID of the peer port.
     */
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }  
  
    /**
     * Get network address
     * 
     * @return  network address
     */
    public String getNetworkAddress() {
        return networkAddress;
    }

    /**
     * Set network address
     * 
     * @param  id  ID of the port.
     */
    public void setNetworkAddress(String networkAddress) {
        this.networkAddress = networkAddress;
    }

    /**
     * Get network length
     * 
     * @return  network length
     */
    public int getNetworkLength() {
        return networkLength;
    }

    /**
     * Set network address
     * portAddress
     * @param  id  ID of the port.
     */
    public void setNetworkLength(int networkLength) {
        this.networkLength = networkLength;
    }
    
    /**
     * Get port address
     * 
     * @return  port address
     */
    public String getPortAddress() {
        return portAddress;
    }

    /**
     * Set port address
     * 
     * @param  address  Address of the port.
     */
    public void setPortAddress(String portAddress) {
        this.portAddress = portAddress;
    } 
    
    /**
     * Get port type
     * 
     * @return  port type
     */
    public String getType() {
        return type;
    }

    /**
     * Set port type
     * 
     * @param  type  Port type.
     */
    public void setType(String type) {
        this.type = type;
    }     
}
