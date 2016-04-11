#!/usr/bin/env python
#  Copyright (C) 2016 Midokura SARL
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

import argparse
import datetime
import subprocess
import sys
import time


def main(argv):
    parser = argparse.ArgumentParser(
        description='Backup zookeeper data to a timestamped file name')

    parser.add_argument('-z', '--zk_server', type=str,
                        help='ZK server in <SERVER:PORT> format')
    args = parser.parse_args()

    if args.zk_server:
        zk_server = args.zk_server
    else:
        zk_server = "127.0.0.1:2181"

    ts = datetime.datetime.fromtimestamp(time.time()).strftime("%Y%m%d%H%M%S")
    filename = "zk_original.backup." + ts
    cmd = 'zkdump -z ' + zk_server + ' -d -o ' + filename
    subprocess.call(cmd, stderr=subprocess.STDOUT, shell=True)


if __name__ == '__main__':
    main(sys.argv)
