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
package org.midonet.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import static org.midonet.util.DataDumper.dumpAsByteArrayDeclaration;

public class DataDumperTest {
    @Test
    public void testDumpAsByteArrayDeclaration() throws Exception {
        assertEquals("{\n" +
                         "        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01\n" +
                         "    }",
                     dumpAsByteArrayDeclaration(
                         new byte[]{
                             (byte) 0, (byte) 0, (byte) 0, (byte) 1
                         }, 0, 4));
    }
    @Test

    public void testDumpAsByteArrayDeclaration2() throws Exception {

        assertEquals("{\n" +
                         "        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x02\n" +
                         "    }",
                     dumpAsByteArrayDeclaration(
                         new byte[]{
                             (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 2
                         }, 0, 5));
    }

    @Test
    public void testDumpAsByteArrayDeclaration3() throws Exception {

        assertEquals("{\n" +
                         "        (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x02\n" +
                         "    }",
                     dumpAsByteArrayDeclaration(
                         new byte[]{
                             (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 2
                         }, 1, 4));
    }

    @Test
    public void testDumpAsByteArrayDeclaration4() throws Exception {

        assertEquals("{\n" +
                         "        (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x02, (byte)0x00, (byte)0x00,\n" +
                         "        (byte)0x01, (byte)0x02, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x02\n" +
                         "    }",
                     dumpAsByteArrayDeclaration(
                         new byte[]{
                             (byte) 0,
                             (byte) 0, (byte) 0, (byte) 1, (byte) 2, (byte) 0, (byte) 0, (byte) 1,
                             (byte) 2, (byte) 0, (byte) 0, (byte) 1, (byte) 2,
                             (byte) 0, (byte) 0, (byte) 1, (byte) 2, (byte) 0, (byte) 0, (byte) 1,
                             (byte) 2, (byte) 0, (byte) 0, (byte) 1, (byte) 2
                         }, 1, 12));
    }
}
