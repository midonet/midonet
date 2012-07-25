/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import static com.midokura.util.DataDumper.dumpAsByteArrayDeclaration;

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
