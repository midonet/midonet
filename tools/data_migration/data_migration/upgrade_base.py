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

import midonet.neutron.db.task_db as task


class TopoQueryFilter(object):
    def func_filter(self, filter_obj=None):
        """
        :return: dict[str, any]|None
        """
        return None

    def post_filter(self, object_list=list()):
        """
        :type object_list: list[dict[str,any]]
        """
        pass


class IDFilter(TopoQueryFilter):
    def __init__(self, foreign_key):
        """
        :type foreign_key: str
        """
        self.foreign_key = foreign_key

    def func_filter(self, filter_obj=None):
        if not filter_obj:
            raise UpgradeScriptException(
                'Cannot filter based on ID when the comparing object is NULL.')
        if 'id' not in filter_obj:
            raise UpgradeScriptException(
                'Cannot filter on an object with no "id" field: ' +
                str(filter_obj))

        return {self.foreign_key: [str(filter_obj['id'])]}


class ListFilter(TopoQueryFilter):
    def __init__(self, check_key, check_list):
        """
        :type check_key: str
        :type check_list: list[str]
        """
        self.check_key = check_key
        self.check_list = check_list

    def func_filter(self, filter_obj=None):
        return {
            self.check_key: self.check_list
        }


class MinLengthFilter(TopoQueryFilter):
    def __init__(self, key, min_len=1):
        """
        :type key: str
        :type min_len: int
        """
        self.key = key
        self.min_len = min_len

    def post_filter(self, object_list=list()):
        for obj in object_list:
            if self.key not in obj or len(obj[self.key]) < self.min_len:
                object_list.remove(obj)


class TopoQuery(object):
    def __init__(self, key, func, task_model,
                 filter_list=list(),
                 sub_key=None, subquery_list=list(),
                 task_type=task.CREATE,
                 task_custom_add=None):
        """
        :type key: str
        :type func: callable
        :type task_model: str
        :type filter_list: list[TopoQueryFilter]
        :type sub_key: str|None
        :type subquery_list: list[TopoQuery]
        :type task_type: str
        :type task_custom_add: callable | None
        """
        self.key = key
        self.func = func
        self.task_model = task_model
        self.filter_list = filter_list
        self.sub_key = sub_key
        self.subquery_list = subquery_list
        self.task_type = task_type
        self.task_custom_add = task_custom_add


def get_objects_from_query(log, ctx, query, parent_obj=None, depth=0):
    retmap = {query.key: {}}
    submap = retmap[query.key]

    log.debug("\t" * depth + "[" + query.key + "]")

    filters = {}
    for f in query.filter_list:
        new_filter = f.func_filter(parent_obj)
        if new_filter:
            filters.update(new_filter)

    object_list = query.func(
        context=ctx,
        filters=filters if filters else None)

    for f in query.filter_list:
        f.post_filter(object_list)

    for obj in object_list:
        """:type: dict[str, any]"""
        if 'id' not in obj:
            raise UpgradeScriptException(
                'Trying to parse an object with no ID field: ' + str(obj))

        singular_noun = (query.key[:-1]
                         if query.key.endswith('s')
                         else query.key)
        log.debug("\t" * (depth + 1) + "[" + singular_noun + " " +
                  obj['id'] + "]: " + str(obj))

        if query.sub_key:
            submap[obj['id']] = {}
            submap[obj['id']][query.sub_key] = obj
        else:
            submap[obj['id']] = obj

        for sq in query.subquery_list:
            submap[obj['id']].update(
                get_objects_from_query(log=log, ctx=ctx,
                                       query=sq, parent_obj=obj,
                                       depth=depth + 2))

    return retmap


def create_task_from_query(log, topo, query):
    """
    :type log: logging.logger
    :type topo: dict[str,any]
    :type query: TopoQuery
    """
    submap = topo[query.key]
    sub_transaction_list = []

    if query.task_custom_add:
        for oid, obj in submap.iteritems():
            sub_transaction_list += query.task_custom_add(oid, obj)
        return sub_transaction_list

    for oid, obj in submap.iteritems():
        neutron_obj = obj if not query.sub_key else obj[query.sub_key]
        log.debug("Creating network translation for: " + str(oid))
        sub_transaction_list.append(
            {'type': query.task_type,
             'data_type': query.task_model,
             'resource_id': oid,
             'tenant_id': (neutron_obj['tenant_id']
                           if neutron_obj['tenant_id']
                           else 'admin'),
             'data': neutron_obj})

        for sq in query.subquery_list:
            add_tasks = create_task_from_query(log=log,
                                               topo=obj,
                                               query=sq)
            if add_tasks:
                sub_transaction_list += add_tasks

        return sub_transaction_list


class UpgradeScriptException(Exception):
    def __init__(self, msg):
        super(UpgradeScriptException, self).__init__()
        self.msg = msg

    def __repr__(self):
        return self.msg

    def __str__(self):
        return self.msg
