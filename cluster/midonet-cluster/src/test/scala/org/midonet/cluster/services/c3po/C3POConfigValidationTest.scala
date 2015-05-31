/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.c3po

import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import org.midonet.cluster.C3POConfig
import org.midonet.cluster.services.ScheduledMinion.CfgParamUndefErrMsg

@RunWith(classOf[JUnitRunner])
class C3POConfigValidationTest extends FlatSpec with Matchers {

    val dfltCnxnStr = "jdbc:sqlite:file:taskdb?mode=memory&cache=shared"
    val dfltJdbcClass = "org.sqlite.JDBC"

    private def validateConfig(jdbcClass: String, cnxnStr: String): Unit = {
        val cfg = mock(classOf[C3POConfig])
        when(cfg.jdbcDriver).thenReturn(jdbcClass)
        when(cfg.connectionString).thenReturn(cnxnStr)
        C3POMinion.validateConfig(cfg)
    }

    "C3POMinion.validateConfig()" should "accept a valid config" in {
        validateConfig(dfltJdbcClass, dfltCnxnStr)
    }

    it should "trim whitespace from config parameters" in {
        validateConfig(s" $dfltJdbcClass   ", s"   $dfltCnxnStr ")
    }

    it should "reject an empty driver class string" in {
        val ex = the[IllegalArgumentException] thrownBy
                 validateConfig(" ", dfltCnxnStr)
        ex.getMessage shouldBe
        CfgParamUndefErrMsg.format(C3POMinion.JdbcDriverCfgKey)
    }

    it should "reject an empty connection string" in {
        val ex = the[IllegalArgumentException] thrownBy
                 validateConfig(dfltJdbcClass, " ")
        ex.getMessage shouldBe
        CfgParamUndefErrMsg.format(C3POMinion.CnxnStrCfgKey)
    }

    it should "reject an unrecognized class name" in {
        val ex = the[ClassNotFoundException] thrownBy
                 validateConfig("blah", dfltCnxnStr)
        ex.getMessage shouldBe
        C3POMinion.JdbcDriverClassNotFoundErrMsg.format("blah")
    }

    it should "reject a driver class not derived from java.sql.Driver" in {
        val ex = the[IllegalArgumentException] thrownBy
                 validateConfig("java.lang.String", dfltCnxnStr)
        ex.getMessage shouldBe
        C3POMinion.NotDriverSubclassErrMsg.format("java.lang.String")
    }

    it should "reject a connection string not accepted by the driver" in {
        val cnxnStr = dfltCnxnStr.replace(":", "/")
        val ex = the[IllegalArgumentException] thrownBy
                 validateConfig(dfltJdbcClass, cnxnStr)
        ex.getMessage shouldBe C3POMinion.InvalidCnxnStrErrMsg.format(cnxnStr)
    }
}
