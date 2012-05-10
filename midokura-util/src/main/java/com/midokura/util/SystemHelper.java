/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util;

/**
 * Simple class to infer the target operating system from the os.name system
 * property.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/30/12
 */
public class SystemHelper {
    public enum OsType {
        Unix, Linux, Solaris, Windows, Mac, Unknown
    }

    public static OsType getOsType() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("mac")) {
            return OsType.Mac;
        }

        if (osName.contains("win")) {
            return OsType.Windows;
        }

        if (osName.contains("nux")) {
            return OsType.Linux;
        }

        if (osName.contains("nix")) {
            return OsType.Unix;
        }

        if (osName.contains("sunos")) {
            return OsType.Solaris;
        }

        return OsType.Unknown;
    }
}
