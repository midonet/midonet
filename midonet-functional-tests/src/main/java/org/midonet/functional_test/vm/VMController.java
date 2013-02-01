/*
 * Copyright 2011 Midokura Europe SARL
 */
package org.midonet.functional_test.vm;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 11/10/11
 * Time: 3:46 PM
 */
public interface VMController {

    public void shutdown();

    public void startup();

    public void destroy();

    public boolean isRunning();

    public String getNetworkMacAddress();
    
    public String getHostName();
    
    public String getDomainName();
}
