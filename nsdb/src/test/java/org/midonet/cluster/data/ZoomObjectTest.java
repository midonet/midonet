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
package org.midonet.cluster.data;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

import org.junit.Test;

import org.midonet.cluster.models.TestModels;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.midonet.cluster.models.TestModels.FakeDevice;
import static org.midonet.cluster.models.TestModels.TestMessage;

public class ZoomObjectTest {

    @Test
    public void testConversionWithStaticMethods() {
        TestMessage message = buildMessage();

        TestableZoomObject pojo =
            ZoomConvert.fromProto(message, TestableZoomObject.class);

        assertPojo(message, pojo);
        assertEquals(pojo.after, pojo.int32Field);
        assertEquals(pojo.baseAfter, pojo.baseField);
        assertEquals(pojo.before, 0);
        assertEquals(pojo.baseBefore, 0);

        TestMessage proto = pojo.toProto(TestMessage.class);

        assertProto(message, proto);
        assertEquals(pojo.after, pojo.int32Field);
        assertEquals(pojo.baseAfter, pojo.baseField);
        assertEquals(pojo.before, pojo.int32Field);
        assertEquals(pojo.baseBefore, pojo.baseField);
    }

    @Test
    public void testConversionWithInstance() {
        TestMessage message = buildMessage();

        TestableZoomObject pojo = new TestableZoomObject();
        TestableZoomObject object = ZoomConvert.fromProto(message, pojo);

        assertEquals(pojo, object);

        assertPojo(message, pojo);
        assertEquals(pojo.after, pojo.int32Field);
        assertEquals(pojo.baseAfter, pojo.baseField);
        assertEquals(pojo.before, 0);
        assertEquals(pojo.baseBefore, 0);

        TestMessage proto = pojo.toProto(TestMessage.class);

        assertProto(message, proto);
        assertEquals(pojo.after, pojo.int32Field);
        assertEquals(pojo.baseAfter, pojo.baseField);
        assertEquals(pojo.before, pojo.int32Field);
        assertEquals(pojo.baseBefore, pojo.baseField);
    }

    @Test
    public void testConversionForByteFields() {
        TestableZoomObject2 pojo = new TestableZoomObject2();
        TestMessage proto = pojo.toProto(TestMessage.class);

        assertTrue(pojo.compare(proto));

        TestableZoomObject2 convertedPojo = ZoomConvert.fromProto(proto, TestableZoomObject2.class);
        assertEquals(pojo, convertedPojo);
    }

    @Test
    public void testConversionWithConstructor() {
        FakeDevice message = buildDevice();

        DeviceWithConstructor pojo =
            ZoomConvert.fromProto(message, DeviceWithConstructor.class);

        assertEquals(pojo.id, UUIDUtil.fromProto(message.getId()));
        assertEquals(pojo.name, message.getName());
        assertEquals(pojo.portIds, message.getPortIdsList());
    }

    static TestMessage buildMessage() {
        byte bytes[] = new byte[16];
        Random random = new Random();
        random.nextBytes(bytes);

        return TestModels.TestMessage.newBuilder()
            .setDoubleField(random.nextDouble())
            .setFloatField(random.nextFloat())
            .setInt32Field(random.nextInt())
            .setInt64Field(random.nextLong())
            .setUint32Field(random.nextInt())
            .setUint64Field(random.nextLong())
            .setSint32Field(random.nextInt())
            .setSint64Field(random.nextLong())
            .setFixed32Field(random.nextInt())
            .setFixed64Field(random.nextLong())
            .setSfixed32Field(random.nextInt())
            .setSfixed64Field(random.nextLong())
            .setBoolField(random.nextBoolean())
            .setStringField(java.util.UUID.randomUUID().toString())
            .setByteField((byte) (random.nextInt() & 0x7F))
            .setShortField((short) (random.nextInt() & 0x7FFF))
            .setByteStringField(ByteString.copyFrom(bytes))
            .setByteArrayField(ByteString.copyFrom(bytes))
            .setBaseField(random.nextInt())
            .addInt32PrimitiveArray(random.nextInt())
            .addInt32PrimitiveArray(random.nextInt())
            .addInt32InstanceArray(random.nextInt())
            .addInt32InstanceArray(random.nextInt())
            .addInt32List(random.nextInt())
            .addInt32List(random.nextInt())
            .addStringList(java.util.UUID.randomUUID().toString())
            .addStringList(java.util.UUID.randomUUID().toString())
            .addBoolList(random.nextBoolean())
            .addBoolList(random.nextBoolean())
            .setUuidField(UUIDUtil.toProto(java.util.UUID.randomUUID()))
            .addUuidArray(UUIDUtil.toProto(java.util.UUID.randomUUID()))
            .addUuidArray(UUIDUtil.toProto(java.util.UUID.randomUUID()))
            .addUuidList(UUIDUtil.toProto(java.util.UUID.randomUUID()))
            .addUuidList(UUIDUtil.toProto(java.util.UUID.randomUUID()))
            .setDeviceField(buildDevice())
            .addDeviceArray(buildDevice())
            .addDeviceArray(buildDevice())
            .addDeviceList(buildDevice())
            .addDeviceList(buildDevice())
            .setEnumField(TestMessage.Enum.SECOND)
            .addAddressList(IPAddressUtil.toProto(IPv4Addr.random()))
            .addAddressList(IPAddressUtil.toProto(IPv4Addr.random()))
            .addSubnetList(IPSubnetUtil.toProto(IPv4Subnet.fromCidr("1.2.3.4/24")))
            .addSubnetList(IPSubnetUtil.toProto(IPv4Subnet.fromCidr("5.6.7.8/24")))
            .build();
    }

    static FakeDevice buildDevice() {
        return FakeDevice.newBuilder()
            .setId(UUIDUtil.randomUuidProto())
            .setName(UUIDUtil.randomUuidProto().toString())
            .addPortIds(UUIDUtil.randomUuidProto().toString())
            .addPortIds(UUIDUtil.randomUuidProto().toString())
            .build();
    }

    static void assertPojo(TestMessage message, TestableZoomObject pojo) {
        assertEquals(pojo.doubleField, message.getDoubleField(), 0.1);
        assertEquals(pojo.floatField, message.getFloatField(), 0.1);
        assertEquals(pojo.int32Field, message.getInt32Field());
        assertEquals(pojo.int64Field, message.getInt64Field());
        assertEquals(pojo.uint32Field, message.getUint32Field());
        assertEquals(pojo.uint64Field, message.getUint64Field());
        assertEquals(pojo.sint32Field, message.getSint32Field());
        assertEquals(pojo.sint64Field, message.getSint64Field());
        assertEquals(pojo.fixed32Field, message.getFixed32Field());
        assertEquals(pojo.fixed64Field, message.getFixed64Field());
        assertEquals(pojo.sfixed32Field, message.getSfixed32Field());
        assertEquals(pojo.sfixed64Field, message.getSfixed64Field());
        assertEquals(pojo.boolField, message.getBoolField());
        assertEquals(pojo.stringField, message.getStringField());
        assertEquals(pojo.byteField, message.getByteField());
        assertEquals(pojo.shortField, message.getShortField());
        assertEquals(pojo.byteStringField, message.getByteStringField());
        assertEquals(pojo.baseField, message.getBaseField());
        assertArrayEquals(pojo.byteArrayField,
                          message.getByteArrayField().toByteArray());

        assertArray(pojo.int32PrimitiveArray,
                    message.getInt32PrimitiveArrayList().toArray());
        assertArray(pojo.int32InstanceArray,
                    message.getInt32InstanceArrayList().toArray());
        assertEquals(pojo.int32List, message.getInt32ListList());
        assertEquals(pojo.stringList, message.getStringListList());
        assertEquals(pojo.boolList, message.getBoolListList());

        assertEquals(pojo.uuidField, UUIDUtil.fromProto(message.getUuidField()));
        for (int index = 0; index < message.getUuidArrayCount(); index++) {
            assertEquals(UUIDUtil.toProto(pojo.uuidArray[index]),
                         message.getUuidArray(index));
        }
        for (int index = 0; index < message.getUuidListCount(); index++) {
            assertEquals(UUIDUtil.toProto(pojo.uuidList.get(index)),
                         message.getUuidList(index));
        }

        assertDevice(pojo.deviceField, message.getDeviceField());
        for (int index = 0; index < message.getDeviceArrayCount(); index++) {
            assertDevice(pojo.deviceArray[index],
                         message.getDeviceArray(index));
        }
        for (int index = 0; index < message.getDeviceListCount(); index++) {
            assertDevice(pojo.deviceList.get(index),
                         message.getDeviceList(index));
        }
        assertEquals(pojo.enumField, TestableZoomObject.Enum.SECOND);

        for(int index = 0; index < message.getAddressListCount(); index++) {
            assertEquals(pojo.addressList.get(index),
                         message.getAddressList(index).getAddress());
        }
        for(int index = 0; index < message.getSubnetListCount(); index++) {
            assertEquals(pojo.subnetList.get(index),
                         IPSubnetUtil.fromProto(message.getSubnetList(index)).toString());
            assertEquals(pojo.subnetList2.get(index),
                         IPSubnetUtil.fromProto(message.getSubnetList(index)));
        }
    }

    static void assertProto(TestMessage message, TestMessage proto) {
        assertEquals(proto, message);
    }

    static void assertArray(Object left, Object right) {
        assertEquals(Array.getLength(left), Array.getLength(right));
        for (int index = 0; index < Array.getLength(left); index++) {
            assertEquals(Array.get(left, index), Array.get(right, index));
        }
    }

    static void assertDevice(Device pojo, FakeDevice proto) {
        assertEquals(UUIDUtil.toProto(pojo.id), proto.getId());
        assertEquals(pojo.name, proto.getName());
        for (int index = 0; index < proto.getPortIdsCount(); index++) {
            assertEquals(pojo.portIds.get(index), proto.getPortIds(index));
        }
    }

    @SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
    @ZoomClass(clazz = TestModels.FakeDevice.class)
    static class Device extends ZoomObject {
        @ZoomField(name = "id")
        private UUID id;
        @ZoomField(name = "name")
        private String name;
        @ZoomField(name = "port_ids")
        private List<String> portIds;
    }

    public static class DeviceWithConstructor extends ZoomObject {
        public final UUID id;
        public final String name;
        public final List<String> portIds;
        @Zoom
        private DeviceWithConstructor(
            @ZoomField(name = "id") UUID id,
            @ZoomField(name = "name") String name,
            @ZoomField(name = "port_ids") List<String> portIds) {
            this.id = id;
            this.name = name;
            this.portIds = portIds;
        }
    }

    static abstract class BaseZoomObject extends ZoomObject {
        @ZoomField(name = "base_field")
        protected int baseField;

        protected int baseAfter;
        protected int baseBefore;

        @Override
        public void afterFromProto(Message proto) {
            baseAfter = baseField;
        }
        @Override
        public void beforeToProto() {
            baseBefore = baseField;
        }
    }

    @SuppressWarnings({"unused", "MismatchedReadAndWriteOfArray",
                       "MismatchedQueryAndUpdateOfCollection"})
    static class TestableZoomObject extends BaseZoomObject {

        @ZoomEnum(clazz = TestMessage.Enum.class)
        enum Enum {
            @ZoomEnumValue(value = "FIRST")
            FIRST,
            @ZoomEnumValue(value = "SECOND")
            SECOND,
            @ZoomEnumValue(value = "THIRD")
            THIRD
        }

        @ZoomField(name = "double_field")
        private double doubleField;
        @ZoomField(name = "float_field")
        private float floatField;
        @ZoomField(name = "int32_field")
        private int int32Field;
        @ZoomField(name = "int64_field")
        private long int64Field;
        @ZoomField(name = "uint32_field")
        private int uint32Field;
        @ZoomField(name = "uint64_field")
        private long uint64Field;
        @ZoomField(name = "sint32_field")
        private int sint32Field;
        @ZoomField(name = "sint64_field")
        private long sint64Field;
        @ZoomField(name = "fixed32_field")
        private int fixed32Field;
        @ZoomField(name = "fixed64_field")
        private long fixed64Field;
        @ZoomField(name = "sfixed32_field")
        private int sfixed32Field;
        @ZoomField(name = "sfixed64_field")
        private long sfixed64Field;
        @ZoomField(name = "bool_field")
        private boolean boolField;
        @ZoomField(name = "string_field")
        private String stringField;
        @ZoomField(name = "byte_field")
        private byte byteField;
        @ZoomField(name = "short_field")
        private short shortField;
        @ZoomField(name = "byte_string_field")
        private ByteString byteStringField;
        @ZoomField(name = "byte_array_field")
        private byte[] byteArrayField;

        @ZoomField(name = "int32_primitive_array")
        private int[] int32PrimitiveArray;
        @ZoomField(name = "int32_instance_array")
        private Integer[] int32InstanceArray;

        @ZoomField(name = "int32_list")
        private List<Integer> int32List;
        @ZoomField(name = "string_list")
        private List<String> stringList;
        @ZoomField(name = "bool_list")
        private List<Boolean> boolList;

        @ZoomField(name = "uuid_field")
        private java.util.UUID uuidField;
        @ZoomField(name = "uuid_array")
        private java.util.UUID[] uuidArray;
        @ZoomField(name = "uuid_list")
        private List<java.util.UUID> uuidList;

        @ZoomField(name = "device_field")
        private Device deviceField;
        @ZoomField(name = "device_array")
        private Device[] deviceArray;
        @ZoomField(name = "device_list")
        private List<Device> deviceList;

        @ZoomField(name = "enum_field")
        private Enum enumField;

        @ZoomField(name = "address_list", converter = IPAddressUtil.Converter.class)
        private List<String> addressList;
        @ZoomField(name = "subnet_list", converter = IPSubnetUtil.Converter.class)
        private List<String> subnetList;
        @ZoomField(name = "subnet_list", converter = IPSubnetUtil.Converter.class)
        private List<IPSubnet<?>> subnetList2;

        private int before;
        private int after;

        @Override
        public void afterFromProto(Message proto) {
            super.afterFromProto(proto);
            after = int32Field;
        }
        @Override
        public void beforeToProto() {
            super.beforeToProto();
            before = int32Field;
        }
    }

    // This class is used to test conversion of proto int32 and repeated int32
    // fields to java Byte and Set<Byte> fields respectively.
    @ZoomClass(clazz = TestModels.TestMessage.class)
    static class TestableZoomObject2 extends ZoomObject {
        @ZoomField(name = "int32_field")
        private Byte byteField = 7;
        @ZoomField(name = "int32_list")
        private Set<Byte> byteSet =
            new HashSet<>(Arrays.asList((byte) 8, (byte) 42));

        public boolean compare(TestMessage message) {
            boolean setsEqual = message.getInt32ListCount() == byteSet.size();
            for (Integer nb : message.getInt32ListList())
                setsEqual &= byteSet.contains(nb.byteValue());

            return message.hasInt32Field() &&
                   ((byte) message.getInt32Field() == byteField) &&
                   setsEqual;
        }

        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof TestableZoomObject2)) return false;
            TestableZoomObject2 o = (TestableZoomObject2) obj;
            return null != byteField && null != byteSet &&
                   byteField.equals(o.byteField) &&
                   byteSet.equals(o.byteSet);
        }
    }
}
