# Copyright (C) 2016 Midokura SARL
# All Rights Reserved.
#
#    Licensed under the Apache License, Version 2.0 (the "License"); you may
#    not use this file except in compliance with the License. You may obtain
#    a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#    License for the specific language governing permissions and limitations
#    under the License.

import ConfigParser
import os

zk_backup_filename = "./zk_original.backup"
temp_mn_conf_settings = "/tmp/mn-conf.settings"


class UpgradeScriptException(Exception):
    def __init__(self, msg):
        super(UpgradeScriptException, self).__init__()
        self.msg = msg

    def __repr__(self):
        return self.msg

    def __str__(self):
        return self.msg


class QueryFilter(object):
    def func_filter(self):
        """
        :return: dict[str, any]|None
        """
        return None

    def post_filter(self, object_list=list()):
        """
        :type object_list: list[dict[str,any]]
        """
        pass


class ListFilter(QueryFilter):
    def __init__(self, check_key, check_list):
        """
        :type check_key: str
        :type check_list: list[str]
        """
        self.check_key = check_key
        self.check_list = check_list

    def func_filter(self):
        return self.check_key, self.check_list


class MinLengthFilter(QueryFilter):
    def __init__(self, field, min_len=1):
        """
        :type field: str
        :type min_len: int
        """
        self.field = field
        self.min_len = min_len

    def post_filter(self, object_list=list()):
        for obj in object_list:
            if self.field not in obj or len(obj[self.field]) < self.min_len:
                object_list.remove(obj)


def get_neutron_objects(key, func, context, log,
                        filter_list=list()):
    retmap = {key: {}}
    submap = retmap[key]

    log.debug("\n[" + key + "]")

    filters = {}
    for f in filter_list:
        new_filter = f.func_filter()
        if new_filter:
            filters.update({new_filter[0]: new_filter[1]})

    object_list = func(
        context=context,
        filters=filters if filters else None)

    for f in filter_list:
        f.post_filter(object_list)

    for obj in object_list:
        """:type: dict[str, any]"""
        if 'id' not in obj:
            raise UpgradeScriptException(
                'Trying to parse an object with no ID field: ' + str(obj))

        singular_noun = (key[:-1]
                         if key.endswith('s')
                         else key)
        log.debug("\t[" + singular_noun + " " +
                  obj['id'] + "]: " + str(obj))

        submap[obj['id']] = obj

    return retmap


def get_zk_server():
    zk_line = os.getenv("MIDO_ZOOKEEPER_HOST", None)
    if not zk_line:
        for ini in ['~/.midonetrc', '/etc/midonet/midonet.conf',
                    '/etc/midolman/midolman.conf']:
            ini_file = ConfigParser.ConfigParser()
            ini_file.read(ini)
            try:
                zk_line = ini_file.get('zookeeper', 'zookeeper_hosts')
            except ConfigParser.NoSectionError:
                continue
            except ConfigParser.NoOptionError:
                continue
    if not zk_line:
        raise ValueError('ZK server info not found in environment var or '
                         'in midonet config files.')
    return zk_line.split(",")[-1]
