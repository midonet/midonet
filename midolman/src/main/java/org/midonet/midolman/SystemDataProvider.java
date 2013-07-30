/*
 * Copyright 2013 Midokura PTE
 */
package org.midonet.midolman;

import org.midonet.midolman.state.StateAccessException;

/**
 * This interface defines methods to provide data version information.
 */
public interface SystemDataProvider {

    public String getWriteVersion() throws StateAccessException;

    public boolean writeVersionExists() throws StateAccessException;

    public void setWriteVersion(String version) throws StateAccessException;

    public boolean systemUpgradeStateExists() throws StateAccessException;

    public boolean isBeforeWriteVersion(String version)
            throws StateAccessException;
}
