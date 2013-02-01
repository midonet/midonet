/*
* Copyright 2011 Midokura Europe SARL
*/
package org.midonet.util.ssh.jsch;

import com.jcraft.jsch.UserInfo;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 5:41 PM
 */
public class PasswordCredentialsUserInfo implements UserInfo {

    String password;

    public PasswordCredentialsUserInfo(String password) {
        this.password = password;
    }

    @Override
    public String getPassphrase() {
        return null;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public boolean promptPassword(String message) {
        return true;
    }

    @Override
    public boolean promptPassphrase(String message) {
        return false;
    }

    @Override
    public boolean promptYesNo(String message) {
        return true;
    }

    @Override
    public void showMessage(String message) {
        //
    }
}
