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
