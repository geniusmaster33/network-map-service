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
package io.cordite.networkmap.service

import io.cordite.networkmap.serialisation.SerializationEnvironment
import io.cordite.networkmap.storage.NetworkParameterInputsStorage
import io.cordite.networkmap.utils.getFreePort
import io.cordite.networkmap.utils.onSuccess
import io.cordite.networkmap.utils.readFiles
import io.cordite.networkmap.utils.setupDefaultInputFiles
import io.vertx.core.Vertx
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import net.corda.core.utilities.millis
import net.corda.core.utilities.seconds
import net.corda.node.services.network.NetworkMapClient
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.internal.TestNodeInfoBuilder
import org.apache.commons.io.FileUtils
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import kotlin.test.*

@RunWith(VertxUnitRunner::class)
class NetworkMapServiceTest {

  companion object {
    init {
      SerializationEnvironment.init()
    }

    val CACHE_TIMEOUT = 1.millis
    val NETWORK_PARAM_UPDATE_DELAY = 5.seconds
    val NETWORK_MAP_QUEUE_DELAY = 1.seconds
  }

  private var vertx = Vertx.vertx()
  private val dbDirectory = createTempDir()
  private val port = getFreePort()

  private lateinit var service: NetworkMapService

  @Before
  fun before(context: TestContext) {
    vertx = Vertx.vertx()

    val fRead = vertx.fileSystem().readFiles("/Users/fuzz/tmp")
    val async = context.async()
    fRead.setHandler { async.complete() }
    async.await()


    val path = dbDirectory.absolutePath
    println("db path: $path")
    println("port   : $port")

    setupDefaultInputFiles(dbDirectory)

    this.service = NetworkMapService(dbDirectory = dbDirectory,
      user = InMemoryUser.createUser("", "sa", ""),
      port = port,
      cacheTimeout = CACHE_TIMEOUT,
      networkParamUpdateDelay = NETWORK_PARAM_UPDATE_DELAY,
      networkMapQueuedUpdateDelay = NETWORK_MAP_QUEUE_DELAY,
      tls = false,
      vertx = vertx
    )

    service.start().setHandler(context.asyncAssertSuccess())
  }

  @After
  fun after(context: TestContext) {
    vertx.close(context.asyncAssertSuccess())
  }

  @Test
  fun `that we can retrieve network map and parameters and they are correct`(context: TestContext) {
    val nmc = createNetworkMapClient(context)
    val nmp = nmc.getNetworkParameters(nmc.getNetworkMap().payload.networkParameterHash)
    val notaries = nmp.verified().notaries

    context.assertEquals(2, notaries.size)
    context.assertEquals(1, notaries.filter { it.validating }.count())
    context.assertEquals(1, notaries.filter { !it.validating }.count())

    val nis = getNetworkParties(nmc)
    context.assertEquals(0, nis.size)
  }

  @Test
  fun `that "my-host" is localhost`(context: TestContext) {
    val nmc = createNetworkMapClient(context)
    val hostname = nmc.myPublicHostname()
    context.assertEquals("localhost", hostname)
  }

  @Test
  fun `that we can add a new node`(context: TestContext) {
    val nmc = createNetworkMapClient(context)
    val tnib = TestNodeInfoBuilder()
    tnib.addIdentity(ALICE_NAME)
    val sni = tnib.buildWithSigned()
    nmc.publish(sni.signed)
    Thread.sleep(NETWORK_MAP_QUEUE_DELAY.toMillis() * 2)
    val nm = nmc.getNetworkMap().payload
    val nhs = nm.nodeInfoHashes
    context.assertEquals(1, nhs.size)
    assertEquals(sni.signed.raw.hash, nhs[0])
  }

  @Test(expected = NullPointerException::class)
  fun `that we cannot register the same node name with a different key`(context: TestContext) {
    val nmc = createNetworkMapClient(context)

    val sni1 = TestNodeInfoBuilder().let {
      it.addIdentity(ALICE_NAME)
      it.buildWithSigned()
    }
    nmc.publish(sni1.signed)
    Thread.sleep(NETWORK_MAP_QUEUE_DELAY.toMillis() * 2)
    val nm = nmc.getNetworkMap().payload
    val nhs = nm.nodeInfoHashes
    context.assertEquals(1, nhs.size)
    assertEquals(sni1.signed.raw.hash, nhs[0])

    val sni2 = TestNodeInfoBuilder().let {
      it.addIdentity(ALICE_NAME)
      it.buildWithSigned()
    }

    val pk1 = sni1.nodeInfo.legalIdentities.first().owningKey
    val pk2 = sni2.nodeInfo.legalIdentities.first().owningKey
    assertNotEquals(pk1, pk2)
    nmc.publish(sni2.signed) // <-- will throw a meaningless NPE see https://github.com/corda/corda/issues/3442
  }


  @Test
  fun `that we can modify the network parameters`(context: TestContext) {
    val nmc = createNetworkMapClient(context)
    deleteValidatingNotaries(dbDirectory)
    Thread.sleep(NetworkParameterInputsStorage.DEFAULT_WATCH_DELAY)
    Thread.sleep(NETWORK_MAP_QUEUE_DELAY.toMillis() * 2)
    val nm = nmc.getNetworkMap().payload
    assertNotNull(nm.parametersUpdate, "expecting parameter update plan")
    val deadLine = nm.parametersUpdate!!.updateDeadline
    val delay = Duration.between(Instant.now(), deadLine)
    assert(delay > Duration.ZERO && delay <= NETWORK_PARAM_UPDATE_DELAY)
    Thread.sleep(delay.toMillis() * 2)
    val nm2 = nmc.getNetworkMap().payload
    assertNull(nm2.parametersUpdate)
    val nmp = nmc.getNetworkParameters(nm2.networkParameterHash).verified()
    assertEquals(1, nmp.notaries.size)
    assertTrue(nmp.notaries.all { !it.validating })
  }

  private fun getNetworkParties(nmc: NetworkMapClient) =
    nmc.getNetworkMap().payload.nodeInfoHashes.map { nmc.getNodeInfo(it) }


  private fun createNetworkMapClient(context: TestContext): NetworkMapClient {
    val async = context.async()
    service.certificateAndKeyPairStorage.get(NetworkMapService.SIGNING_CERT_NAME)
      .onSuccess {
        context.put<X509Certificate>("cert", it.certificate)
        async.complete()
      }
      .setHandler(context.asyncAssertSuccess())
    async.awaitSuccess()
    return NetworkMapClient(URL("http://localhost:$port"), DEV_ROOT_CA.certificate)
  }

  private fun createTempDir(): File {
    return Files.createTempDirectory("nms-test-").toFile()
      .apply {
        mkdirs()
        deleteOnExit()
      }
  }

  private fun deleteValidatingNotaries(directory: File) {
    val inputs = File(directory, NetworkParameterInputsStorage.DEFAULT_DIR_NAME)
    FileUtils.cleanDirectory(File(inputs, NetworkParameterInputsStorage.DEFAULT_DIR_VALIDATING_NOTARIES))
  }
}