package com.midokura.midonet.smoketest.vm;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 11/10/11
 * Time: 3:46 PM
 */
public interface VMController {

    public void shutdown();

    public void startup();
    
    public String getNetworkMacAddress();
}
