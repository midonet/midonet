/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.version;

import java.lang.Integer;
import java.lang.NumberFormatException;
import java.util.Comparator;

/*
 * class that acts as a placeholder for the version of this
 * instance of Midolman. This will be compared to the
 * write_version in ZK to make sure we are writing the
 * correct version of objects.
 */
public class VersionComparator implements Comparator<String> {

    private class Version {
        final int minor;
        final int major;

        public int getMajor() { return this.major; }
        public int getMinor() { return this.minor; }

        public Version(String versionString) throws IllegalArgumentException {
            String[] parts = versionString.split("\\.");
            if (parts.length != 2) throw new IllegalArgumentException();
            try {
                this.major = Integer.parseInt(parts[0]);
                this.minor = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException();
            }
        }

        public boolean isLaterThan(Version otherVersion) {
            if (this.major > otherVersion.getMajor()) {
                return true;
            } else if (this.major == otherVersion.getMajor()) {
                return (this.minor > otherVersion.getMinor());
            }
            return false;
        }
    }

    /**
     * compare.
     * compares two version strings with the following format: [int].[int]
     * These are considered the major and minor versions respectively. If
     * the major versions differ, then the version with the higher major
     * field is the newer version. If the major versions are equal, then the
     * version with the higher minor version field is the newer version.
     *
     * The format of the version string has a possibility of changing
     * to any other format in the future. This is currently the first
     * version, so we can assume that if we don't understand the
     * version format, it is a newer version. If we don't understand
     * the format of either of the arguments, we are in trouble.
     * In that case, we will consider them equal, as they are both
     * equally gibberish.
     *
     * @return -1 if v1 is 'less' than v2, 0 if they are equal, 1 otherwise
     */
    @Override
    public int compare(String v1, String v2) {
        Version versionOne = null;
        boolean versionOneValid = true;
        Version versionTwo = null;
        boolean versionTwoValid = true;
        try {
            versionOne = new Version(v1);
        } catch (IllegalArgumentException e) {
            /*
             * VersionOne is not recognized. This means that it is potentially
             * a later version than we understand.
             */
            versionOneValid = false;
        }

        try {
            versionTwo = new Version(v2);
        } catch (IllegalArgumentException e) {
            /*
            * VersionTwo is not recognized. This means that it is potentially
            * a later version than we understand.
            */
            versionTwoValid = false;
        }

        if (!versionOneValid && versionTwoValid) {
            return 1;
        } else if (versionOneValid && !versionTwoValid) {
            return -1;
        } else if (!versionOneValid && !versionTwoValid) {
            return 0;
        }

        /*
         * both formats are understood. just compare them now.
         */
        if (versionOne.isLaterThan(versionTwo)) {
            return 1;
        } else if (versionTwo.isLaterThan(versionOne)) {
            return -1;
        } else {
            return 0;
        }
    }

    /*
     * Because VersionComparator doesn't have any internal state, any
     * VersionComparator object will be equal to any other.
     */
    @Override
    public boolean equals(Object obj) {
        return (obj instanceof VersionComparator);
    }
}
