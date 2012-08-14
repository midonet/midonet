// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn


trait Device {
    def process(portmatch: PortMatch): PortMatch
}
