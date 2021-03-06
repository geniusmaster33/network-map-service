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
@file:Suppress("DEPRECATION")

package io.cordite.networkmap.service

import io.cordite.networkmap.changeset.Change
import io.cordite.networkmap.changeset.changeSet
import io.cordite.networkmap.serialisation.deserializeOnContext
import io.cordite.networkmap.utils.*
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.swagger.annotations.ApiOperation
import io.vertx.core.Future
import io.vertx.core.Future.failedFuture
import io.vertx.core.Future.succeededFuture
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.node.NetworkParameters
import net.corda.core.node.NotaryInfo
import net.corda.core.serialization.serialize
import net.corda.core.utilities.loggerFor
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.ParametersUpdate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.ws.rs.core.MediaType

/**
 * Event processor for the network map
 * This consumes networkparameter inputs changes; and nodeinfo updates
 * and rebuilds the set of files to be served by the server
 */
class NetworkMapServiceProcessor(
  private val vertx: Vertx,
  private val storages: ServiceStorages,
  private val certificateManager: CertificateManager,
  /**
   * how long a change to the network parameters will be queued (and signalled as such in the [NetworkMap.parametersUpdate]) before being applied
   */
  private val networkMapQueueDelay: Duration,
  val paramUpdateDelay: Duration
) {
  companion object {
    private val logger = loggerFor<NetworkMapServiceProcessor>()
    const val EXECUTOR = "network-map-pool"

    private val templateNetworkParameters = NetworkParameters(
      minimumPlatformVersion = 1,
      notaries = listOf(),
      maxMessageSize = 10485760,
      maxTransactionSize = Int.MAX_VALUE,
      modifiedTime = Instant.now(),
      epoch = 1, // this will be incremented when used for the first time
      whitelistedContractImplementations = mapOf()
    )
  }

  // we use a single thread to queue changes to the map, to ensure consistency
  private val executor = vertx.createSharedWorkerExecutor(EXECUTOR, 1)
  private var networkMapRebuildTimerId: Long? = null
  private lateinit var certs: CertificateAndKeyPair

  fun start(): Future<Unit> {
    certs = certificateManager.networkMapCertAndKeyPair
    return execute {
      createNetworkParameters()
        .compose { createNetworkMap() }
    }
  }

  fun stop() {}

  fun addNode(signedNodeInfo: SignedNodeInfo): Future<Unit> {
    try {
      logger.info("adding signed nodeinfo ${signedNodeInfo.raw.hash}")
      val ni = signedNodeInfo.verified()
      val partyAndCerts = ni.legalIdentitiesAndCerts

      // TODO: optimise this to use the database, and avoid loading all nodes into memory

        return storages.nodeInfo.getAll()
        .onSuccess { nodes ->
          // flatten the current nodes to Party -> PublicKey map
          val registered = nodes.flatMap { namedSignedNodeInfo ->
            namedSignedNodeInfo.value.verified().legalIdentitiesAndCerts.map { partyAndCertificate ->
              partyAndCertificate.party.name to partyAndCertificate.owningKey
            }
          }.toMap()

          // now filter the party and certs of the nodeinfo we're trying to register
          val registeredWithDifferentKey = partyAndCerts.filter {
            // looking for where the public keys differ
            registered[it.party.name].let { pk ->
              pk != null && pk != it.owningKey
            }
          }
          if (registeredWithDifferentKey.any()) {
            val names = registeredWithDifferentKey.joinToString("\n") { it.name.toString() }
            val msg = "node failed to registered because the following names have already been registered with different public keys $names"
            logger.warn(msg)
            throw RuntimeException(msg)
          }
        }
        .compose {
          val hash = signedNodeInfo.raw.sha256()
          storages.nodeInfo.put(hash.toString(), signedNodeInfo)
            .compose { scheduleNetworkMapRebuild() }
            .onSuccess { logger.info("node ${signedNodeInfo.raw.hash} for party ${ni.legalIdentities} added") }
        }
        .catch { ex ->
          logger.error("failed to add node", ex)
        }
    } catch (err: Throwable) {
      logger.error("failed to add node", err)
      return failedFuture(err)
    }
  }

  // BEGIN: web entry points

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = """For the non validating notary to upload its signed NodeInfo object to the network map",
    Please ignore the way swagger presents this. To upload a notary info file use:
      <code>
      curl -X POST -H "Authorization: Bearer &lt;token&gt;" -H "accept: text/plain" -H  "Content-Type: application/octet-stream" --data-binary @nodeInfo-007A0CAE8EECC5C9BE40337C8303F39D34592AA481F3153B0E16524BAD467533 http://localhost:8080//admin/api/notaries/nonValidating
      </code>
      """,
    consumes = MediaType.APPLICATION_OCTET_STREAM
  )
  fun postNonValidatingNotaryNodeInfo(nodeInfoBuffer: Buffer): Future<String> {
    logger.info("adding non-validating notary")
    return try {
      val nodeInfo = nodeInfoBuffer.bytes.deserializeOnContext<SignedNodeInfo>().verified()
      val updater = changeSet(Change.AddNotary(NotaryInfo(nodeInfo.legalIdentities.first(), false)))
      updateNetworkParameters(updater, "admin updating adding non-validating notary").map { "OK" }
    } catch (err: Throwable) {
      logger.error("failed to add validating notary", err)
      Future.failedFuture(err)
    }
  }

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = """For the validating notary to upload its signed NodeInfo object to the network map.
    Please ignore the way swagger presents this. To upload a notary info file use:
      <code>
      curl -X POST -H "Authorization: Bearer &lt;token&gt;" -H "accept: text/plain" -H  "Content-Type: application/octet-stream" --data-binary @nodeInfo-007A0CAE8EECC5C9BE40337C8303F39D34592AA481F3153B0E16524BAD467533 http://localhost:8080//admin/api/notaries/validating
      </code>
      """,
    consumes = MediaType.APPLICATION_OCTET_STREAM
  )
  fun postValidatingNotaryNodeInfo(nodeInfoBuffer: Buffer): Future<String> {
    logger.info("adding validating notary")
    return try {
      val nodeInfo = nodeInfoBuffer.bytes.deserializeOnContext<SignedNodeInfo>().verified()
      val updater = changeSet(Change.AddNotary(NotaryInfo(nodeInfo.legalIdentities.first(), true)))
      updateNetworkParameters(updater, "admin adding validating notary").map { "OK" }
    } catch (err: Throwable) {
      logger.error("failed to add validating notary", err)
      Future.failedFuture(err)
    }
  }

  @ApiOperation(value = "append to the whitelist")
  fun appendWhitelist(append: String): Future<Unit> {
    logger.info("appending to whitelist:\n$append")
    return try {
      logger.info("web request to append to whitelist $append")
      val parsed = append.toWhiteList()
      val updater = changeSet(Change.AppendWhiteList(parsed))
      updateNetworkParameters(updater, "admin appending to the whitelist")
        .onSuccess {
          logger.info("completed append to whitelist")
        }
        .catch {
          logger.error("failed to append to whitelist")
        }
        .mapUnit()
    } catch (err: Throwable) {
      Future.failedFuture(err)
    }
  }

  @ApiOperation(value = "replace the whitelist")
  fun replaceWhitelist(replacement: String): Future<Unit> {
    logger.info("replacing current whitelist with: \n$replacement")
    return try {
      val parsed = replacement.toWhiteList()
      val updater = changeSet(Change.ReplaceWhiteList(parsed))
      updateNetworkParameters(updater, "admin replacing the whitelist").mapUnit()
    } catch (err: Throwable) {
      Future.failedFuture(err)
    }
  }


  @ApiOperation(value = "clears the whitelist")
  fun clearWhitelist(): Future<Unit> {
    logger.info("clearing current whitelist")
    return try {
      val updater = changeSet(Change.ClearWhiteList)
      updateNetworkParameters(updater, "admin clearing the whitelist").mapUnit()
    } catch (err: Throwable) {
      Future.failedFuture(err)
    }
  }

  @ApiOperation(value = "serve whitelist", response = String::class)
  fun serveWhitelist(routingContext: RoutingContext) {
    logger.trace("serving current whitelist")
    storages.getCurrentNetworkParameters()
      .map {
        it.whitelistedContractImplementations
          .flatMap { entry ->
            entry.value.map { attachmentId ->
              "${entry.key}:$attachmentId"
            }
          }.joinToString("\n")
      }
      .onSuccess { whitelist ->
        routingContext.response()
          .setNoCache().putHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN).end(whitelist)
      }
      .catch { routingContext.setNoCache().end(it) }
  }

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = "delete a validating notary with the node key")
  fun deleteValidatingNotary(nodeKey: String): Future<Unit> {
    logger.info("deleting validating notary $nodeKey")
    return try {
      val nameHash = SecureHash.parse(nodeKey)
      updateNetworkParameters(changeSet(Change.RemoveNotary(nameHash)), "admin deleting validating notary").mapUnit()
    } catch (err: Throwable) {
      Future.failedFuture(err)
    }
  }

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = "delete a non-validating notary with the node key")
  fun deleteNonValidatingNotary(nodeKey: String): Future<Unit> {
    logger.info("deleting non-validating notary $nodeKey")
    return try {
      val nameHash = SecureHash.parse(nodeKey)
      updateNetworkParameters(changeSet(Change.RemoveNotary(nameHash)), "admin deleting non-validating notary").mapUnit()
    } catch (err: Throwable) {
      Future.failedFuture(err)
    }
  }

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = "delete a node by its key")
  fun deleteNode(nodeKey: String): Future<Unit> {
    logger.info("deleting node $nodeKey")
    return storages.nodeInfo.delete(nodeKey)
      .compose { createNetworkMap() }
  }

  @ApiOperation(value = "serve set of notaries", response = SimpleNotaryInfo::class, responseContainer = "List")
  fun serveNotaries(routingContext: RoutingContext) {
    logger.trace("serving current notaries")
    storages.getCurrentNetworkParameters()
      .map { networkParameters ->
        networkParameters.notaries.map {
          SimpleNotaryInfo(it.identity.name.serialize().hash.toString(), it)
        }
      }
      .onSuccess {
        simpleNodeInfos -> routingContext.setNoCache().end(simpleNodeInfos)
      }
      .catch {
        routingContext.setNoCache().end(it)
      }
  }

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = "retrieve all nodeinfos", responseContainer = "List", response = SimpleNodeInfo::class)
  fun serveNodes(context: RoutingContext) {
    logger.info("serving current set of node")
    context.setNoCache()
    storages.nodeInfo.getAll()
      .onSuccess { mapOfNodes ->
        context.end(mapOfNodes.map { namedNodeInfo ->
          val node = namedNodeInfo.value.verified()
          SimpleNodeInfo(
            nodeKey = namedNodeInfo.key,
            addresses = node.addresses,
            parties = node.legalIdentitiesAndCerts.map { NameAndKey(it.name, it.owningKey) },
            platformVersion = node.platformVersion
          )
        })
      }
      .catch { context.end(it) }
  }

  @Suppress("MemberVisibilityCanBePrivate")
  @ApiOperation(value = "Retrieve the current network parameters",
    produces = MediaType.APPLICATION_JSON, response = NetworkParameters::class)
  fun getCurrentNetworkParameters(context: RoutingContext) {
    logger.trace("serving current network parameters")
    storages.getCurrentNetworkParameters()
      .onSuccess { context.end(it) }
      .catch { context.end(it) }
  }

  // END: web entry points

  // BEGIN: core functions

  internal fun updateNetworkParameters(update: (NetworkParameters) -> NetworkParameters, description: String = "") : Future<Unit> {
    return updateNetworkParameters(update, description, Instant.now().plus(paramUpdateDelay))
  }

  /**
   * we use this function to schedule a rebuild of the network map
   * we do this to avoid masses of network map rebuilds in the case of several nodes joining
   * or DoS attack
   */
  private fun scheduleNetworkMapRebuild() : Future<Unit> {
    logger.info("queuing network map rebuild in $networkMapQueueDelay")
    // cancel the old timer
    networkMapRebuildTimerId = networkMapRebuildTimerId?.let { vertx.cancelTimer(it); null }
    // setup a timer to rebuild the network map
    return if (networkMapQueueDelay == Duration.ZERO) {
      createNetworkMap()
    } else {
      vertx.setTimer(Math.max(1, networkMapQueueDelay.toMillis())) {
        // we'll queue this on the executor thread to ensure consistency
        execute { createNetworkMap() }
      }
      succeededFuture(Unit)
    }
  }

  private fun createNetworkParameters(): Future<SecureHash> {
    logger.info("creating network parameters ...")
    logger.info("retrieving current network parameter ...")
    return storages.getCurrentSignedNetworkParameters().map { it.raw.hash }
      .recover {
        logger.info("could not find network parameters - creating one from the template")
        storages.storeNetworkParameters(templateNetworkParameters, certs)
          .compose { hash -> storages.storeCurrentParametersHash(hash) }
          .onSuccess { result ->
            logger.info("network parameters saved $result")
          }
          .catch { err ->
            logger.info("failed to create network parameters", err)
          }
      }
  }

  internal fun updateNetworkParameters(update: (NetworkParameters) -> NetworkParameters, description: String, activation: Instant) : Future<Unit> {
    return execute {
      logger.info("updating network parameters")
      storages.getCurrentNetworkParameters()
        .map { update(it) } // apply changeset and sign
        .compose { newNetworkParameters ->
          storages.storeNetworkParameters(newNetworkParameters, certs)
        }
        .compose { hash ->
          if (activation <= Instant.now()) {
            storages.storeCurrentParametersHash(hash)
              .compose { createNetworkMap() }
          } else {
            storages.storeNextParametersUpdate(ParametersUpdate(hash, description, activation))
              .compose { scheduleNetworkMapRebuild() }
          }
        }
    }
  }

  private val uniqueIdFountain = AtomicInteger(1)
  private fun createNetworkMap(): Future<Unit> {
    val id = uniqueIdFountain.getAndIncrement()
    logger.info("($id) creating network map")
    // collect the inputs from disk
    val fNodes = storages.nodeInfo.getKeys().map { keys -> keys.map { key -> SecureHash.parse(key) } }
    val fParamUpdate = storages.getParameterUpdateOrNull()
    val fLatestParamHash = storages.getCurrentNetworkParametersHash()

    // when all collected
    return all(fNodes, fParamUpdate, fLatestParamHash)
      .map {
        logger.info("($id) building network map object")
        // build the network map
        NetworkMap(
          networkParameterHash = fLatestParamHash.result(),
          parametersUpdate = fParamUpdate.result(),
          nodeInfoHashes = fNodes.result()
        ).also { nm -> logger.info("($id) the new map will be $nm")}
      }.compose { nm ->
        val snm = nm.sign(certs)
        storages.storeNetworkMap(snm)
      }
      .compose {
        logger.info("($id) checking if parameter update needs to be scheduled")
        storages.getParameterUpdateOrNull()
          .compose { parametersUpdate ->
            try {
              if (parametersUpdate != null) {
                val delay = Duration.between(Instant.now(), parametersUpdate.updateDeadline)
                logger.info("($id) scheduling parameter update ${parametersUpdate.newParametersHash} '${parametersUpdate.description}' in $delay")
                vertx.setTimer(Math.max(1, delay.toMillis())) {
                  logger.info("($id) applying parameter update ${parametersUpdate.newParametersHash}")
                  storages.storeCurrentParametersHash(parametersUpdate.newParametersHash)
                    .compose { storages.resetNextParametersUpdate() }
                    .compose { createNetworkMap() }
                    .onSuccess { logger.info("($id) parameter update applied ${parametersUpdate.newParametersHash} '${parametersUpdate.description}'") }
                    .catch { logger.error("($id) failed to apply parameter update ${parametersUpdate.newParametersHash} '${parametersUpdate.description}'", it) }
                }
              } else {
                logger.info("($id) no parameters update scheduled")
              }
              succeededFuture(Unit)
            } catch (err: Throwable) {
              logger.error("($id) failed to schedule timer", err)
              failedFuture<Unit>(err)
            }
          }
          .recover {
            logger.info("($id) no planned parameter update found - scheduling nothing")
            succeededFuture(Unit)
          }
      }
      .catch {
        logger.error("($id) failed to create network map", it)
      }
  }

  // END: core functions

  // BEGIN: utility functions

  /**
   * Execute a blocking operation on a non-eventloop thread
   */
  private fun <T> execute(fn: () -> Future<T>): Future<T> {
    return withFuture { result ->
      executor.executeBlocking<T>({
        fn().setHandler(it.completer())
      }, {
        result.handle(it)
      })
    }
  }
  // END: utility functions
}