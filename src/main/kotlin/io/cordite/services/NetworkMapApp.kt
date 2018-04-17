package io.cordite.services

import com.fasterxml.jackson.databind.module.SimpleModule
import io.cordite.services.keystore.toX509KeyStore
import io.cordite.services.serialisation.PublicKeyDeserializer
import io.cordite.services.serialisation.PublicKeySerializer
import io.cordite.services.storage.InMemorySignedNodeInfoStorage
import io.cordite.services.storage.InMemoryWhiteListStorage
import io.cordite.services.storage.SignedNodeInfoStorage
import io.cordite.services.storage.WhitelistStorage
import io.cordite.services.utils.*
import io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_OCTET_STREAM
import io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.Json
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler
import net.corda.client.jackson.JacksonSupport
import net.corda.client.rpc.internal.KryoClientSerializationScheme
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.signWithCert
import net.corda.core.node.NetworkParameters
import net.corda.core.node.NotaryInfo
import net.corda.core.node.services.AttachmentId
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.serialization.serialize
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.seconds
import net.corda.core.utilities.toBase58String
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.ParametersUpdate
import net.corda.nodeapi.internal.network.SignedNetworkParameters
import net.corda.nodeapi.internal.serialization.AMQP_P2P_CONTEXT
import net.corda.nodeapi.internal.serialization.SerializationFactoryImpl
import net.corda.nodeapi.internal.serialization.amqp.AMQPClientSerializationScheme
import java.io.File
import java.security.PublicKey
import java.time.Instant
import javax.security.auth.x500.X500Principal

open class NetworkMapApp(private val port: Int,
                         private val notaryDir: File,
                         private val nodeInfoStorage: SignedNodeInfoStorage,
                         private val whiteListStorage: WhitelistStorage) : AbstractVerticle() {
  companion object {
    val logger = loggerFor<NetworkMapApp>()
    val jksRegex = ".*\\.jks".toRegex()
    const val WEB_ROOT = "/network-map"
    const val WEB_API = "/api"

    @JvmStatic
    fun main(args: Array<String>) {
      val options = Options()
      val portOption = options.addOption("port", "8080", "web port")
      val notaryDirectory = options.addOption("notary.dir", "notary-certificates", "notary cert directory")
      if (args.contains("--help")) {
        options.printOptions()
        return
      }
      val port = portOption.value.toInt()
      val notaryDir = notaryDirectory.value.toFile()
      NetworkMapApp(port, notaryDir, InMemorySignedNodeInfoStorage(), InMemoryWhiteListStorage()).deploy()
    }

    private fun initialiseJackson() {
      val module = SimpleModule()
          .addDeserializer(CordaX500Name::class.java, JacksonSupport.CordaX500NameDeserializer)
          .addSerializer(CordaX500Name::class.java, JacksonSupport.CordaX500NameSerializer)
          .addSerializer(PublicKey::class.java, PublicKeySerializer())
          .addDeserializer(PublicKey::class.java, PublicKeyDeserializer())
      Json.mapper.registerModule(module)
      Json.prettyMapper.registerModule(module)
    }

    private fun initialiseSerialisationEnvironment() {
      if (nodeSerializationEnv == null) {
        nodeSerializationEnv = SerializationEnvironmentImpl(
            SerializationFactoryImpl().apply {
              registerScheme(KryoClientSerializationScheme())
              registerScheme(AMQPClientSerializationScheme())
            },
            AMQP_P2P_CONTEXT)
      }
    }
  }

  private val stubNetworkParameters = NetworkParameters(
      minimumPlatformVersion = 1,
      notaries = readNotaries(),
      maxMessageSize = 10485760,
      maxTransactionSize = Int.MAX_VALUE,
      modifiedTime = Instant.now(),
      epoch = 10,
      whitelistedContractImplementations = whiteListStorage.getAllAsMap())

  private val cacheTimeout = 10.seconds
  private val networkMapCa = createDevNetworkMapCa()
  private lateinit var networkParameters: NetworkParameters
  private lateinit var signedNetParams: SignedNetworkParameters
  private lateinit var parametersUpdate: ParametersUpdate

  init {
    initialiseJackson()
    initialiseSerialisationEnvironment()
    updateNetworkParameters(stubNetworkParameters, "first update")
  }

  protected fun deploy() {
    Vertx.vertx().deployVerticle(this)
  }

  override fun start(startFuture: Future<Void>) {
    logger.info("starting network map with port: $port")
    setupNotaryCertificateWatch()
    createHttpServer(createRouter()).setHandler(startFuture.completer())
  }

  private fun setupNotaryCertificateWatch() {
    scheduleDigest(DirectoryDigest(notaryDir, jksRegex), vertx) {
      val notaries = readNotaries()
      updateNetworkParameters(networkParameters.copy(notaries = notaries, modifiedTime = Instant.now()), "notaries changed")
      notaries.forEach { println("${it.identity.name} - ${it.identity.owningKey.toBase58String()} - ${it.validating}") }
    }
  }

  private fun createHttpServer(router: Router): Future<Void> {
    val result = Future.future<Void>()
    vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(port) {
          if (it.failed()) {
            logger.error("failed to startup", it.cause())
            result.fail(it.cause())
          } else {
            logger.info("network map service started")
            logger.info("api mounted on http://localhost:$port$WEB_ROOT")
            logger.info("website http://localhost:$port")
            result.complete()
          }
        }
    return result
  }

  private fun updateNetworkParameters(networkParameters: NetworkParameters, description: String) {
    this.networkParameters = networkParameters
    this.signedNetParams = networkParameters.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
    this.parametersUpdate = ParametersUpdate(networkParameters.serialize().hash, description, Instant.now())
  }

  private fun readNotaries(): List<NotaryInfo> {
    return notaryDir
        .getFiles(jksRegex)
        .map {
          try {
            it.toX509KeyStore("cordacadevpass")
          } catch (err: Throwable) {
            null
          }
        }
        .filter { it != null }
        .map { it!! }
        .filter { it.aliases().asSequence().contains(X509Utilities.CORDA_CLIENT_CA) }
        .map { it.getCertificate(X509Utilities.CORDA_CLIENT_CA) }
        .distinct()
        .map { NotaryInfo(Party(it), true) }
        .toList()
  }

  private fun scheduleDigest(dd: DirectoryDigest, vertx: Vertx, fnChange: (hash: String) -> Unit) = scheduleDigest("", dd, vertx, fnChange)
  private fun scheduleDigest(lastHash: String, dd: DirectoryDigest, vertx: Vertx, fnChange: (hash: String) -> Unit) {
    vertx.scheduleBlocking(2000) {
      val hash = dd.digest()
      if (lastHash != hash) {
        vertx.runOnContext { fnChange(hash) }
      }
      scheduleDigest(hash, dd, vertx, fnChange)
    }
  }

  private fun createRouter(): Router {
    val router = Router.router(vertx)
    bindCordaNetworkMapAPI(router)
    bindManagementAPI(router)
    bindStatic(router)
    return router
  }

  private fun bindStatic(router: Router) {
    val staticHandler = StaticHandler.create("website").setCachingEnabled(false).setCacheEntryTimeout(1).setMaxCacheSize(1)
    router.get("/*").handler(staticHandler::handle)
  }

  private fun bindManagementAPI(router: Router) {
    router.get("$WEB_API/notaries")
        .handler {
          it.handleExceptions {
            getNotaries()
          }
        }
    router.get("$WEB_API/whitelist")
        .handler {
          it.handleExceptions {
            it.getWhitelist()
          }
        }
    router.put("$WEB_API/whitelist")
        .handler {
          it.request().bodyHandler { body ->
            it.handleExceptions {
              it.putWhitelist(body.toString())
            }
          }
        }
    router.post("$WEB_API/whitelist")
        .handler {
          it.handleExceptions {
            it.request().bodyHandler { body ->
              it.postWhitelist(body.toString())
            }
          }
        }
  }

  private fun bindCordaNetworkMapAPI(router: Router) {
    router.post("$WEB_ROOT/publish")
        .consumes(APPLICATION_OCTET_STREAM.toString())
        .handler {
          it.handleExceptions { postNetworkMap() }
        }
    router.post("$WEB_ROOT/ack-parameters")
        .consumes(APPLICATION_OCTET_STREAM.toString())
        .handler {
          it.handleExceptions {
            postAckNetworkParameters()
          }
        }
    router.get(WEB_ROOT)
        .produces(APPLICATION_OCTET_STREAM.toString())
        .handler { it.handleExceptions { getNetworkMap() } }
    router.get("$WEB_ROOT/node-info/:hash")
        .produces(APPLICATION_OCTET_STREAM.toString())
        .handler {
          it.handleExceptions {
            val hash = SecureHash.parse(request().getParam("hash"))
            getNodeInfo(hash)
          }
        }
    router.get("$WEB_ROOT/network-parameters/:hash")
        .produces(APPLICATION_OCTET_STREAM.toString())
        .handler {
          it.handleExceptions {
            val hash = SecureHash.parse(request().getParam("hash"))
            getNetworkParameters(hash)
          }
        }
  }

  private fun RoutingContext.getWhitelist() {
    val list = whiteListStorage.getAll().map { "${it.first}:${it.second}" }
    val result = list.joinToString("\n")
    response()
        .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
        .putHeader(HttpHeaders.CONTENT_LENGTH, result.length.toString())
        .end(result)
  }

  private fun RoutingContext.putWhitelist(additions: String) {
    val items = parseWhiteList(additions)
    whiteListStorage.add(items)
    updateNetworkParameters(networkParameters.copy(whitelistedContractImplementations = whiteListStorage.getAllAsMap()), "whitelist extended")
    response().end()
  }

  private fun RoutingContext.postWhitelist(replacement: String) {
    val items = parseWhiteList(replacement)
    whiteListStorage.clear()
    whiteListStorage.add(items)
    updateNetworkParameters(networkParameters.copy(whitelistedContractImplementations = whiteListStorage.getAllAsMap()), "whitelist replaced")
    response().end()
  }

  private fun RoutingContext.getNotaries() {
    this.end(networkParameters.notaries)
  }

  private fun RoutingContext.getNetworkMap() {
    val networkMap = NetworkMap(nodeInfoStorage.allHashes(), signedNetParams.raw.hash, parametersUpdate)
    val signedNetworkMap = networkMap.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
    response().apply {
      putHeader(HttpHeaders.CACHE_CONTROL, "max-age=${cacheTimeout.seconds}")
      end(Buffer.buffer(signedNetworkMap.serialize().bytes))
    }
  }

  private fun RoutingContext.postNetworkMap() {
    request().bodyHandler { buffer ->
      val signedNodeInfo = buffer.bytes.deserialize<SignedNodeInfo>()
      signedNodeInfo.verified()
      nodeInfoStorage.store(signedNodeInfo)
      response().setStatusCode(HttpResponseStatus.OK.code()).end()
    }
  }

  private fun RoutingContext.getNodeInfo(hash: SecureHash) {
    nodeInfoStorage.find(hash)?.let {
      val bytes = it.serialize().bytes
      response()
          .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_OCTET_STREAM)
          .putHeader(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
          .end(Buffer.buffer(bytes))
    } ?: run {
      response().setStatusCode(HttpResponseStatus.NOT_FOUND.code()).end()
    }
  }

  private fun createDevNetworkMapCa(rootCa: CertificateAndKeyPair = DEV_ROOT_CA): CertificateAndKeyPair {
    val keyPair = Crypto.generateKeyPair()
    val cert = X509Utilities.createCertificate(
        CertificateType.NETWORK_MAP,
        rootCa.certificate,
        rootCa.keyPair,
        X500Principal("CN=Network Map,O=Cordite,L=London,C=GB"),
        keyPair.public)
    return CertificateAndKeyPair(cert, keyPair)
  }

  private fun RoutingContext.postAckNetworkParameters() {
    request().bodyHandler { buffer ->
      this.handleExceptions {
        val signedParameterHash = buffer.bytes.deserialize<SignedData<SecureHash>>()
        val hash = signedParameterHash.verified()
        val nodeInfo = nodeInfoStorage.find(hash)
        if (nodeInfo != null) {
          logger.info("received acknowledgement from node ${nodeInfo.raw.deserialize().legalIdentities}")
        } else {
          logger.warn("received acknowledgement from unknown node!")
        }
        response().end()
      }
    }
  }

  private fun RoutingContext.getNetworkParameters(hash: SecureHash) {
    val requestedParameters = if (hash == signedNetParams.raw.hash) {
      signedNetParams
    } else null
    requireNotNull(requestedParameters)
    val bytes = requestedParameters!!.serialize().bytes
    response()
        .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_OCTET_STREAM)
        .putHeader(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
        .end(Buffer.buffer(bytes))
  }

  private fun parseWhiteList(additions: String): List<Pair<String, SecureHash.SHA256>> {
    return additions
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.split(':') }
        .map {
          assert(it.size == 2) { "white list entry $it doesn't match pattern <FQN>:<SHA256>" }
          it[0] to AttachmentId.parse(it[1])
        }
  }
}
