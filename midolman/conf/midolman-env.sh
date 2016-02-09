# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

MIDO_HOME=/usr/share/midolman
MIDO_CFG=/etc/midolman
MIDO_LOG_DIR=/var/log/midolman/
MIDO_DEBUG_PORT=8001
JMX_PORT="7200"
MIDO_CFG_FILE=midolman.conf
QUAGGA_DIR=/var/run/quagga
# setting this option will make the agent not run under the watchdog
#WATCHDOG_DISABLE=
WATCHDOG_TIMEOUT=10

# Amount of memory to allocate for the JVM heap.
MAX_HEAP_SIZE="2048M"

# Here we create the arguments that will get passed to the jvm when
# starting midolman.

# enable assertions.  disabling this in production will give a modest
# performance benefit (around 5%).
# JVM_OPTS="$JVM_OPTS -ea"
JVM_OPTS="$JVM_OPTS -XX:+AggressiveOpts"

# enable thread priorities, primarily so we can give periodic tasks
# a lower priority to avoid interfering with client workload
JVM_OPTS="$JVM_OPTS -XX:+UseThreadPriorities"
# allows lowering thread priority without being root.  see
# http://tech.stolsvik.com/2010/01/linux-java-thread-priorities-workaround.html
JVM_OPTS="$JVM_OPTS -XX:ThreadPriorityPolicy=42"

# min and max heap sizes should be set to the same value to avoid
# stop-the-world GC pauses during resize, and so that we can lock the
# heap in memory on startup to prevent any of it from being swapped
# out.
JVM_OPTS="$JVM_OPTS -Xms${MAX_HEAP_SIZE}"
JVM_OPTS="$JVM_OPTS -Xmx${MAX_HEAP_SIZE}"
JVM_OPTS="$JVM_OPTS -XX:HeapDumpPath=/var/log/midolman/"
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError"
JVM_OPTS="$JVM_OPTS -XX:OnOutOfMemoryError=\"kill;-3;%p\""

# Do not use biased locking
JVM_OPTS="$JVM_OPTS -XX:-UseBiasedLocking"

# GC tuning options
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -XX:MaxGCPauseMillis=500"
JVM_OPTS="$JVM_OPTS -XX:InitiatingHeapOccupancyPercent=70"
JVM_OPTS="$JVM_OPTS -XX:SurvivorRatio=8"
JVM_OPTS="$JVM_OPTS -XX:MaxTenuringThreshold=8"
JVM_OPTS="$JVM_OPTS -XX:+UseTLAB"
JVM_OPTS="$JVM_OPTS -XX:+ResizeTLAB"
JVM_OPTS="$JVM_OPTS -XX:TLABSize=2m"
JVM_OPTS="$JVM_OPTS -XX:PretenureSizeThreshold=2m"

# GC logging options
JVM_OPTS="$JVM_OPTS -XX:+PrintGCDetails"
JVM_OPTS="$JVM_OPTS -XX:+PrintGCTimeStamps"
JVM_OPTS="$JVM_OPTS -XX:+PrintClassHistogram"
JVM_OPTS="$JVM_OPTS -XX:+PrintTenuringDistribution"
JVM_OPTS="$JVM_OPTS -XX:+PrintGCApplicationStoppedTime"
JVM_OPTS="$JVM_OPTS -XX:+UseGCLogFileRotation"
JVM_OPTS="$JVM_OPTS -XX:NumberOfGClogFiles=10"
JVM_OPTS="$JVM_OPTS -XX:GCLogFileSize=10M"
JVM_OPTS="$JVM_OPTS -Xloggc:/var/log/midolman/gc-`date +%Y%m%d_%H%M%S`.log"

# uncomment to have Midolman JVM listen for remote debuggers/profilers on port 1414
# JVM_OPTS="$JVM_OPTS -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=1414"

# Prefer binding to IPv4 network intefaces (when net.ipv6.bindv6only=1). See
# http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6342561 (short version:
# comment out this entry to enable IPv6 support).
# JVM_OPTS="$JVM_OPTS -Djava.net.preferIPv4Stack=true"

# uncomment to disable JMX
# JMXDISABLE=true

# jmx: metrics and administration interface
#
# add this if you're having trouble connecting:
# JVM_OPTS="$JVM_OPTS -Djava.rmi.server.hostname=<public name>"
#
# see
# http://blogs.sun.com/jmxetc/entry/troubleshooting_connection_problems_in_jconsole
# for more on configuring JMX through firewalls, etc. (Short version:
# get it working with no firewall first.)
if [ "x$JMXDISABLE" = "x" ] ; then
    JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote"
    JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote.local.only=$JMXLOCALONLY"
    JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote.port=$JMX_PORT"
    JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote.ssl=false"
    JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
    HOSTNAME=`hostname`
    JVM_OPTS="$JVM_OPTS -Djava.rmi.server.hostname=$HOSTNAME"
fi

if [ "$MIDOLMAN_HPROF" = "1" ] ; then
    DATE=$(date +'%H%M%S')
    HPROF_FILENAME=${HPROF_FILENAME:-/tmp/midolman-$DATE.hprof}
    JVM_OPTS="$JVM_OPTS -agentlib:hprof=cpu=samples,depth=100,interval=1,lineno=y,thread=y,file=$HPROF_FILENAME"
fi
