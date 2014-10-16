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

/**
 * Simple helper class to dump a byte array as a java byte array declaration.
 */
public class DataDumper {

    private static final int BYTES_PER_LINE = 6;
    private static final char[] HEX_CHAR = new char[]
        { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

    public static String dumpAsByteArrayDeclaration(byte[] array, int off, int len) {

        StringBuilder buffer = new StringBuilder();

        buffer.append("{\n        ");
        int max = Math.min(off + len, array.length);
        for ( int i = off; i < max; i++ ) {
            buffer.append("(byte)0x")
                  .append(hexChar(array[i], 0))
                  .append(hexChar(array[i], 1));

            if (i != max - 1 ) {
                buffer.append(",");
                if ( (i - off) % BYTES_PER_LINE == (BYTES_PER_LINE - 1) ) {
                    buffer.append("\n        ");
                } else {
                    buffer.append(" ");
                }
            }
        }

        buffer.append("\n    }");
        return buffer.toString();
    }

    private static char hexChar(byte b, int hexChar) {
        return hexChar == 0 ? HEX_CHAR[(b & 0xF0) >> 4] : HEX_CHAR[b & 0x0F];
    }

}
