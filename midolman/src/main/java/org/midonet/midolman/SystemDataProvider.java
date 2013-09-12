/*
 * Copyright 2013 Midokura PTE
 */
package org.midonet.midolman;

import org.midonet.midolman.state.StateAccessException;

import java.util.List;

/**
 * This interface defines methods to provide system data information.
 */
public interface SystemDataProvider {

    public String getWriteVersion()
            throws StateAccessException;

    public boolean writeVersionExists()
            throws StateAccessException;

    public void setWriteVersion(String version)
            throws StateAccessException;

    public boolean systemUpgradeStateExists()
            throws StateAccessException;

    public void setOperationState(String state)
            throws StateAccessException;

    public void setConfigState(String state)
            throws StateAccessException;

    public boolean configReadOnly()
            throws StateAccessException;

    public boolean isBeforeWriteVersion(String version)
            throws StateAccessException;

    public List<String> getVersionsInDeployment()
            throws StateAccessException;

    public List<String> getHostsWithVersion(String version)
            throws StateAccessException;
}
