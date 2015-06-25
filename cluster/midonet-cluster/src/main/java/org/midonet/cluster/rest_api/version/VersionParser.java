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
package org.midonet.cluster.rest_api.version;

import javax.ws.rs.core.MediaType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser class to extract the version number from the media type
 */
public class VersionParser {

    /**
     * A simple regex pattern for the vendor media type format.  It is used
     * to extract data from the media type such as its version.  It relies on
     * using Java regex capture to extract information.  If additional data
     * ever needs to be extracted, update PatternGroup enum to match the group
     * position correctly.
     */
    private static Pattern pattern = Pattern.compile("[^-]+-v(\\d+)\\+.+");

    private enum PatternGroup {
        ALL(0), VERSION(1);

        private final int value;
        PatternGroup(int value) {
            this.value = value;
        }

        public int getValue() {
            return this.value;
        }
    }

    /**
     * Extract the version number from the provided media type string.
     *
     * @param mediaType  Media type to extract the version number from
     * @return  The version number in the media type.  -1 if not found.
     */
    public int getVersion(String mediaType) {

        Matcher matcher = pattern.matcher(mediaType);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(PatternGroup.VERSION
                    .getValue()));
        } else {
            return -1;
        }
    }

    /**
     * Extract the version number from the provided
     * {@link javax.ws.rs.core.MediaType} object.
     *
     * @param mediaType  {@link javax.ws.rs.core.MediaType} to extract the
     *     version number from
     * @return  The version number in the {@link javax.ws.rs.core.MediaType}
     *     object.  -1 if not found
     */
    public int getVersion(MediaType mediaType) {
        return getVersion(mediaType.toString());
    }

}
