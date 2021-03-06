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

import com.mongodb.reactivestreams.client.MongoClient
import io.bluebank.braid.core.logging.loggerFor
import io.cordite.networkmap.storage.file.CertificateAndKeyPairStorage
import io.cordite.networkmap.storage.file.NetworkParameterInputsStorage
import io.cordite.networkmap.storage.file.TextStorage
import io.cordite.networkmap.storage.mongo.*
import io.cordite.networkmap.utils.all
import io.cordite.networkmap.utils.mapUnit
import io.cordite.networkmap.utils.onSuccess
import io.cordite.networkmap.utils.sign
import io.vertx.core.Future
import io.vertx.core.Vertx
import net.corda.core.crypto.SecureHash
import net.corda.core.node.NetworkParameters
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.network.ParametersUpdate
import net.corda.nodeapi.internal.network.SignedNetworkMap
import net.corda.nodeapi.internal.network.SignedNetworkParameters
import java.io.File

class ServiceStorages(
  private val vertx: Vertx,
  private val dbDirectory: File,
  mongoClient: MongoClient,
  mongoDatabase: String
) {
  companion object {
    private val logger = loggerFor<ServiceStorages>()
    const val CURRENT_PARAMETERS = "current-parameters" // key for current network parameters hash
    const val NEXT_PARAMS_UPDATE = "next-params-update" // key for next params update hash
    const val NETWORK_MAP_KEY = "latest-network-map" // key for latest network map hash
  }

  val certAndKeys = CertificateAndKeyPairStorage(vertx, dbDirectory)
  val input = NetworkParameterInputsStorage(dbDirectory, vertx)
  val networkMap = SignedNetworkMapStorage(mongoClient, mongoDatabase)
  val nodeInfo = SignedNodeInfoStorage(mongoClient, mongoDatabase)
  val networkParameters = SignedNetworkParametersStorage(mongoClient, mongoDatabase)
  private val parameterUpdate = ParametersUpdateStorage(mongoClient, mongoDatabase)
  val text = MongoTextStorage(mongoClient, mongoDatabase)

  fun setupStorage(): Future<Unit> {
    return all(
      input.makeDirs(),
      networkParameters.migrate(io.cordite.networkmap.storage.file.SignedNetworkParametersStorage(vertx, dbDirectory)),
      parameterUpdate.migrate(io.cordite.networkmap.storage.file.ParametersUpdateStorage(vertx, dbDirectory)),
      networkMap.migrate(io.cordite.networkmap.storage.file.SignedNetworkMapStorage(vertx, dbDirectory)),
      text.migrate(TextStorage(vertx, dbDirectory)),
      nodeInfo.migrate(io.cordite.networkmap.storage.file.SignedNodeInfoStorage(vertx, dbDirectory)),
      certAndKeys.makeDirs()
    ).mapUnit()
  }

  fun getCurrentNetworkParametersHash() : Future<SecureHash> {
    return text.get(CURRENT_PARAMETERS).map { SecureHash.parse(it) }
  }

  fun getCurrentNetworkParameters() : Future<NetworkParameters> {
    return getCurrentSignedNetworkParameters().map { it.verified() }
  }

  fun getCurrentSignedNetworkParameters() : Future<SignedNetworkParameters> {
    return text.get(CURRENT_PARAMETERS).compose { key -> networkParameters.get(key) }
  }

  fun storeCurrentParametersHash(hash: SecureHash) : Future<SecureHash> {
    logger.info("storing current network parameters $hash")
    return text.put(CURRENT_PARAMETERS, hash.toString()).map { hash }
  }

  fun storeNextParametersUpdate(parametersUpdate: ParametersUpdate) : Future<Unit> {
    logger.info("storing next parameter update $parametersUpdate")
    return parameterUpdate.put(NEXT_PARAMS_UPDATE, parametersUpdate)
  }

  fun resetNextParametersUpdate() : Future<Unit> {
    logger.info("resetting next parameter update")
    return parameterUpdate.delete(NEXT_PARAMS_UPDATE)
  }

  fun getParameterUpdateOrNull() : Future<ParametersUpdate?> {
    return parameterUpdate.getOrNull(NEXT_PARAMS_UPDATE)
  }

  fun getParameterUpdate() : Future<ParametersUpdate> {
    return parameterUpdate.get(NEXT_PARAMS_UPDATE)
  }

  fun storeNetworkMap(signedNetworkMap: SignedNetworkMap) : Future<Unit> {
    logger.info("saving network map ${signedNetworkMap.raw.hash} with contents ${signedNetworkMap.verified()}")
    return networkMap.put(NETWORK_MAP_KEY, signedNetworkMap)
      .onSuccess { logger.info("saved network map ${signedNetworkMap.raw.hash}") }
  }

  fun storeNetworkParameters(newParams: NetworkParameters, certs: CertificateAndKeyPair) : Future<SecureHash> {
    val signed = newParams.sign(certs)
    val hash = signed.raw.hash
    logger.info("storing network parameters $hash with values $newParams")
    return networkParameters.put(hash.toString(), signed).map { hash }
  }

}