/**
 *   Copyright 2018, Cordite Foundation.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.cordite.networkmap.changeset

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.NetworkParameters
import net.corda.core.node.NotaryInfo
import net.corda.testing.core.TestIdentity
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeSetTest {
  private val fountain = AtomicInteger(1)

  @Test
  fun `that we can add a new notary info`() {
    val party = createParty()
    val notaryInfo = NotaryInfo(party, false)
    val networkParams = defaultNetworkParameters()
    val newNetworkParams = changeSet(Change.AddNotary(notaryInfo))(networkParams)
    assertEquals(networkParams.eventHorizon, newNetworkParams.eventHorizon)
    assertEquals(networkParams.epoch + 1, newNetworkParams.epoch)
    assertEquals(networkParams.maxMessageSize, newNetworkParams.maxMessageSize)
    assertEquals(networkParams.minimumPlatformVersion, newNetworkParams.minimumPlatformVersion)
    assertEquals(networkParams.whitelistedContractImplementations, newNetworkParams.whitelistedContractImplementations)
    assertEquals(networkParams.maxTransactionSize, newNetworkParams.maxTransactionSize)
    assertTrue(networkParams.modifiedTime <= newNetworkParams.modifiedTime)
    assertEquals(1, newNetworkParams.notaries.size)
    assertEquals(notaryInfo, newNetworkParams.notaries.first())
  }

  private fun defaultNetworkParameters() =
    NetworkParameters(
      minimumPlatformVersion = 3,
      notaries = listOf(),
      maxMessageSize = Int.MAX_VALUE,
      maxTransactionSize = Int.MAX_VALUE,
      modifiedTime = Instant.now(),
      epoch = 1,
      whitelistedContractImplementations = mapOf()
    )

  private fun createParty() : Party {
    return TestIdentity(CordaX500Name("Party${fountain.getAndIncrement()}", "New York", "US")).party
  }
}