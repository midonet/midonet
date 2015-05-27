#! /bin/sh

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

n=$1; shift

if test -d /run; then
    mount --bind /run.$n /run
else
    mount --bind /var/run.$n /var/run
fi
mount  -t tmpfs -o size=50m tmpfs /var/lib/cassandra
mount --bind /var/log/cassandra.$n /var/log/cassandra
mount --bind /etc/cassandra.$n /etc/cassandra

chown -R cassandra.cassandra /var/lib/cassandra

exec $*
