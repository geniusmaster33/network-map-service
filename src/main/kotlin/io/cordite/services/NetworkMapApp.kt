package io.cordite.services

import io.netty.handler.codec.http.HttpHeaderValues
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import net.corda.client.rpc.internal.KryoClientSerializationScheme
import net.corda.core.crypto.Crypto
import net.corda.core.internal.signWithCert
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.serialization.serialize
import net.corda.core.utilities.seconds
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.ParametersUpdate
import net.corda.nodeapi.internal.serialization.AMQP_P2P_CONTEXT
import net.corda.nodeapi.internal.serialization.SerializationFactoryImpl
import net.corda.nodeapi.internal.serialization.amqp.AMQPClientSerializationScheme
import java.time.Instant
import javax.security.auth.x500.X500Principal

class NetworkMapApp(private val port: Int) : AbstractVerticle() {
  companion object {
    val logger = loggerFor(NetworkMapApp::class)
    const val WEB_ROOT = "/network-map"
    val stubNetworkParameters = NetworkParameters(minimumPlatformVersion = 1, notaries = emptyList(), maxMessageSize = 10485760, maxTransactionSize = Int.MAX_VALUE, modifiedTime = Instant.now(), epoch = 10, whitelistedContractImplementations = emptyMap())

    @JvmStatic
    fun main(args: Array<String>) {
      initialiseSerialisationEnvironment()
      val port = getPort()
      NetworkMapApp(port).deploy()
    }

    private fun getPort() : Int {
      return getVariable("port", "8080").toInt()
    }

    private fun getVariable(name: String, default: String) : String {
      return (System.getenv(name) ?: System.getProperty(name) ?: default)
    }

    private fun initialiseSerialisationEnvironment() {
      nodeSerializationEnv = SerializationEnvironmentImpl(
          SerializationFactoryImpl().apply {
            registerScheme(KryoClientSerializationScheme())
            registerScheme(AMQPClientSerializationScheme())
          },
          AMQP_P2P_CONTEXT)
    }

  }

  private var networkParameters = stubNetworkParameters
  private val cacheTimeout = 10.seconds
  private val networkMapCa = createDevNetworkMapCa()
  private val signedNetParams by lazy {
    networkParameters.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
  }

  private val parametersUpdate = ParametersUpdate(networkParameters.serialize().hash, "first update", Instant.now())
  private fun deploy() {
    Vertx.vertx().deployVerticle(this)
  }

  override fun start(startFuture: Future<Void>) {
    logger.info("starting network map with port: $port")

    val router = Router.router(vertx)
    router.get("/").handler {
      it.end("hello, v3")
    }
    router.get(WEB_ROOT)
        .produces(HttpHeaderValues.APPLICATION_OCTET_STREAM.toString())
        .handler {
        it.getNetworkMap()
    }

    vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(port) {
          if (it.failed()) {
            logger.error("failed to startup", it.cause())
            startFuture.fail(it.cause())
          } else {
            logger.info("networkmap service started on http://localhost:$port$WEB_ROOT")
            startFuture.complete()
          }
        }
  }

  private fun RoutingContext.getNetworkMap() {
    val networkMap = NetworkMap(listOf(), signedNetParams.raw.hash, parametersUpdate)
    val signedNetworkMap = networkMap.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
    response().apply {
      putHeader(HttpHeaders.CACHE_CONTROL, "max-age=${cacheTimeout.seconds}")
      end(Buffer.buffer(signedNetworkMap.serialize().bytes))
    }
  }

  fun createDevNetworkMapCa(rootCa: CertificateAndKeyPair = DEV_ROOT_CA): CertificateAndKeyPair {
    val keyPair = Crypto.generateKeyPair()
    val cert = X509Utilities.createCertificate(
        CertificateType.NETWORK_MAP,
        rootCa.certificate,
        rootCa.keyPair,
        X500Principal("CN=Network Map,O=Cordite,L=London,C=GB"),
        keyPair.public)
    return CertificateAndKeyPair(cert, keyPair)
  }
}