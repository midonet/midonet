# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright (c) 2015 Midokura Europe SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import itertools
import uuid

from google.protobuf import descriptor


def proto_to_dict(obj, message_type_map=None):
    """Returns a PyDict that describes a Protobuf object

    If message_type_map is specified, it will be used as a map between protobuf
    types (using full name) and unary methods that return a desired description
    for them. It works in mutual recursion with proto_describe_value
    """
    out = {}
    for field, value in obj.ListFields():
        if field.label == descriptor.FieldDescriptor.LABEL_REPEATED:
            out[field.name] = [proto_describe_value(embedded_field, field,
                                                    message_type_map)
                               for embedded_field in value]
        elif field.is_extension:
            try:
                extensions = out['extensions']
            except KeyError:
                extensions = out['extensions'] = {}

            extensions[field.name] = proto_describe_value(value, field,
                                                          message_type_map)
        else:
            out[field.name] = proto_describe_value(value, field,
                                                   message_type_map)
    return out


def proto_describe_value(obj, field, message_type_map):
    """Returns a Python structure for a Protobuf object

    If message_type_map is specified, it will be used as a map between protobuf
    types (using full name) and unary methods that return a desired description
    for them.
    """
    if field.type == descriptor.FieldDescriptor.TYPE_MESSAGE:
        if message_type_map is None:
            #import ipdb; ipdb.set_trace()
            value = proto_to_dict(obj, message_type_map=message_type_map)
        else:
            formatter = message_type_map.get(field.message_type.full_name)
            if formatter is None:
                value = proto_to_dict(obj, message_type_map=message_type_map)
            else:
                value = formatter(obj)
        return value

    elif field.type == descriptor.FieldDescriptor.TYPE_ENUM:
        value = field.enum_type.values_by_number.get(obj, None).name
        if message_type_map is not None:
            formatter = message_type_map.get(field.full_name)
            if formatter is not None:
                value = formatter(value)
        return value
    else:
        return obj


def int_to_varint(number):
    """Returns a Protobuf varint that encodes the supplied number"""
    data = []
    current = number & 0x7f
    remaining_length = number >> 7  # Move on to the next processing
    while remaining_length:
        byte = chr(0x80 | current)  # It's not the final byte, '1' to msb
        data.append(byte)
        current = remaining_length & 0x7f
        remaining_length >>= 7

    # Handle the last byte, msb is already '0'
    data.append(chr(current))
    return ''.join(data)


def varint_to_int(byte_str):
    """Returns the number(int/long) that was encoded in the Protobuf varint"""
    value = 0
    size = 0
    for current in byte_str:
        value |= (ord(current) & 0x7F) << (size * 7)
        size += 1
    return value


def split_uuid(inp):
    """Splits a uuid.UUID into two 64bit integer halves"""
    value = inp.int
    mask = (2 ** 64) - 1
    msb = (value & (mask << 64)) >> 64
    lsb = value & mask
    return msb, lsb


def uuid_to_UUID(inp):
    """Returns a uuid.UUID object encoding a Protobuf UUID"""
    return uuid.UUID(int=inp.msb << 64 | inp.lsb)


def _bytes_hex(inp):
    """Returns the hexadecimal string that encodes the input bytes"""
    return ''.join('{:02x}'.format(ord(char)) for char in inp)


def _hex_bytes(inp):
    """Returns the bytes encoded in the hexadecimal string"""
    args = [iter(inp)] * 2
    bytes_list = [''.join(tup) for tup in itertools.izip(*args)]
    return ''.join(chr(int(char, 16)) for char in bytes_list)


def encode_delimited(data):
    """Generate message where the data bytes are preceeded by length varint"""
    length = int_to_varint(len(data))
    return length + data


def decode_delimited(data):
    """Return the bytes specified by the length varint"""
    length = []
    pos = 0
    while not _varint_final_byte(data[pos]):
        length.append(data[pos])
        pos += 1

    length.append(data[pos])
    pos += 1
    payload_length = varint_to_int(''.join(length))
    if len(data) < (pos + payload_length):
        raise ValueError('The supplied data is shorter than the codified '
                         'length')
    return data[pos:pos + payload_length]


def get_answer(sock, timeout=5):
    """Return the payload of a protobuf message without the leading varint"""
    sock.settimeout(timeout)
    read = sock.recv(1)
    if read == "":
        return read  # socket closed
    data = [read]
    while not _varint_final_byte(read):
        read = sock.recv(1)
        if read == "":
            return read  # socket closed
        data.append(read)
    length = varint_to_int(''.join(data))

    data = []
    remaining = length
    while remaining > 0:
        read = sock.recv(length)
        remaining -= len(read)
        data.append(read)
    return ''.join(data)


def _varint_final_byte(char):
    """Return True iff the char is the last of current varint"""
    return not ord(char) & 0x80
