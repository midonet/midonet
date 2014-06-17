/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.io

sealed trait ChannelType

case object VirtualMachine extends ChannelType
case object OverlayTunnel extends ChannelType
case object VtepTunnel extends ChannelType
