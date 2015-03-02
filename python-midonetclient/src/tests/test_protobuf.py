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

import ddt
import unittest

from midonetclient.protobuf import utils
import test_pb2

_msg_with_req_fields = dict(
    b = True,
    by = b'\x0bo',
    d = 1.1e+211,
    e = test_pb2.Exhaustive.Third,
    i32 = 2 ** 31 - 1,
    i64 = 2 ** 63 - 1,
    f32 = 2 ** 31 - 1,
    f64 = 2 ** 63 - 1,
    f = 11.11,
    nested = test_pb2.Exhaustive.Nested(nest = 'i am nested'),
    s = 'stringy thing',
    u32 = (2 ** 32) - 1,
    u64 = (2 ** 64) - 1)

_req_fields = _msg_with_req_fields.copy()
_req_fields.update(dict(
    e = 'Third',
    nested = {'nest': u'i am nested'}))


_msg_with_rep_fields = _msg_with_req_fields.copy()
_msg_with_rep_fields.update(dict(
    tags = ['networking', 'virtualization'],
    nests = [test_pb2.Exhaustive.Nested(nest='1st'),
             test_pb2.Exhaustive.Nested(nest='2nd'),
             test_pb2.Exhaustive.Nested(nest='3rd')]))

_rep_fields = _req_fields.copy()
_rep_fields.update(dict(
    tags = ['networking', 'virtualization'],
    nests = [{'nest': '1st'}, {'nest': '2nd'}, {'nest': '3rd'}]))

_msg_with_ext_fields = _msg_with_rep_fields.copy()
_msg_with_ext_fields.update(dict(
    Extensions = {test_pb2.number: 11, test_pb2.name: 'abiko'}))


@ddt.ddt
class TestProtobuf(unittest.TestCase):
    @ddt.data(
        (test_pb2.Exhaustive(**_msg_with_req_fields), _req_fields),
        (test_pb2.Exhaustive(**_msg_with_rep_fields), _rep_fields),
    )
    def test_proto_dict(self, data):
        msg, expected_dict = data
        self.assertEqual(expected_dict, utils.proto_to_dict(msg))

    @ddt.data(
        (test_pb2.Exhaustive(**_msg_with_req_fields), _req_fields),
        (test_pb2.Exhaustive(**_msg_with_rep_fields), _rep_fields),
    )
    def test_proto_dict_with_exts(self, data):
        msg, expected_dict = data

        msg.Extensions[test_pb2.number] = 11
        msg.Extensions[test_pb2.name] = 'abiko'

        expected_dict['extensions'] = {'number': 11, 'name': 'abiko'}
        self.assertEqual(expected_dict, utils.proto_to_dict(msg))


def main():
    unittest.main()

if __name__ == '__main__':
    main()
