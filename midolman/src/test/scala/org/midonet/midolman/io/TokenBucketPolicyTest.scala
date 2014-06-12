/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.io

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{OneInstancePerTest, ShouldMatchers, BeforeAndAfter, FeatureSpec}

import org.apache.commons.configuration.HierarchicalConfiguration

import org.midonet.config.ConfigProvider
import org.midonet.midolman.config.{DatapathConfig, MidolmanConfig}
import org.midonet.odp.ports.{NetDevPort, VxLanTunnelPort, GreTunnelPort}
import org.midonet.util.{TokenBucket, Bucket, TokenBucketTestRate}
import java.util

@RunWith(classOf[JUnitRunner])
class TokenBucketPolicyTest extends FeatureSpec
                            with BeforeAndAfter
                            with ShouldMatchers
                            with OneInstancePerTest {
    var policy: TokenBucketPolicy = _
    val multiplier = 2

    before {
        val configuration = new HierarchicalConfiguration
        configuration.addNodes(DatapathConfig.GROUP_NAME, util.Arrays.asList(
            new HierarchicalConfiguration.Node("global_incoming_burst_capacity", 1),
            new HierarchicalConfiguration.Node("vm_incoming_burst_capacity", 1),
            new HierarchicalConfiguration.Node("tunnel_incoming_burst_capacity", 8),
            new HierarchicalConfiguration.Node("vtep_incoming_burst_capacity", 1)))

        val provider = ConfigProvider.providerForIniConfig(configuration)

        policy = new TokenBucketPolicy(
            provider.getConfig(classOf[MidolmanConfig]),
            new TokenBucketTestRate, multiplier,
            new Bucket(_, 1, null, 0, false))
    }

    feature("Buckets are correctly linked") {
        scenario("Tunnel ports result in a leaf bucket under the root") {
            val tbgre = policy link new GreTunnelPort("gre")
            val tbvxlan = policy link new VxLanTunnelPort("vxlan")

            tbgre.underlyingTokenBucket.getCapacity should be (4)
            tbgre.underlyingTokenBucket.getName should be ("midolman-root/gre")
            tbvxlan.underlyingTokenBucket.getCapacity should be (1)
            tbvxlan.underlyingTokenBucket.getName should be ("midolman-root/vxlan")
        }

        scenario("VM ports result in a leaf bucket under the VMs bucket") {
            val tb = policy link new NetDevPort("vm")
            tb.underlyingTokenBucket.getCapacity should be (1)
            tb.underlyingTokenBucket.getName should be ("midolman-root/vms/vm")
        }

        scenario("The policy ensures the capacity of the root is greater or " +
                 "equal to the capacity of all leaf buckets") {
            val tb1 = policy link new NetDevPort("vm1")
            val tb2 = policy link new NetDevPort("vm2")

            tb1.underlyingTokenBucket.tryGet(1) should be (1)
            tb2.underlyingTokenBucket.tryGet(1) should be (1)
        }
    }

    feature("Buckets are correctly unlinked") {
        scenario("Tunnel ports are correctly unlinked") {
            val grePort = new GreTunnelPort("gre")
            val tbgre = policy.link(grePort)
            val vxlanPort = new VxLanTunnelPort("vxlan")
            val tbvxlan = policy.link(vxlanPort)

            policy unlink grePort
            policy unlink vxlanPort

            tbgre.underlyingTokenBucket.getNumTokens should be (TokenBucket.UNLINKED)
            tbvxlan.underlyingTokenBucket.getNumTokens should be (TokenBucket.UNLINKED)
        }

        scenario("VM ports are correctly unlinked") {
            val port = new NetDevPort("port")
            val tb = policy.link(port)

            policy unlink port

            tb.underlyingTokenBucket.getNumTokens should be (TokenBucket.UNLINKED)
        }
    }
}
