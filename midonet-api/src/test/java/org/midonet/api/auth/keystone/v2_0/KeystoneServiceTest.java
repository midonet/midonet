/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.api.auth.keystone.v2_0;

import java.util.TimeZone;

import org.junit.Test;

import org.midonet.api.auth.keystone.KeystoneInvalidFormatException;

import static org.midonet.api.auth.keystone.v2_0.KeystoneService.parseExpirationDate;

public class KeystoneServiceTest {

    @Test(expected = KeystoneInvalidFormatException.class)
    public void malformedDate() throws Exception {
        parseExpirationDate("malformed");
    }

    @Test
    public void standardFormat() throws Exception {
        String stdFormat = "2015-06-28T08:28:34Z";
        parseExpirationDate(stdFormat);
    }

    @Test
    public void fromFernetTokens() throws Exception {
        String fernetFormat = "2015-06-28T08:39:41.644518Z";
        parseExpirationDate(fernetFormat);
    }

}
