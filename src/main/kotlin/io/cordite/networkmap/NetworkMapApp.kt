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
package io.cordite.networkmap

import io.cordite.networkmap.service.InMemoryUser
import io.cordite.networkmap.service.NetworkMapService
import io.cordite.networkmap.utils.Options
import io.cordite.networkmap.utils.toFile
import net.corda.core.utilities.loggerFor
import java.time.Duration

open class NetworkMapApp  {
  companion object {
    private val logger = loggerFor<NetworkMapApp>()

    @JvmStatic
    fun main(args: Array<String>) {
      val options = Options()
      val portOpt = options.addOption("port", "8080", "web port")
      val dbDirectoryOpt = options.addOption("db", ".db", "database directory for this service")
      val cacheTimeoutOpt = options.addOption("cache.timeout", "2S", "http cache timeout for this service in ISO 8601 duration format")
      val paramUpdateDelayOpt = options.addOption("paramUpdate.delay", "10S", "schedule duration for a parameter update")
      val networkMapUpdateDelayOpt  = options.addOption("networkMap.delay", "1S", "queue time for the network map to update for addition of nodes")
      val usernameOpt = options.addOption("username", "sa", "system admin username")
      val passwordOpt = options.addOption("password", "admin", "system admin password")
      val tlsOpt = options.addOption("tls", "true", "whether TLS is enabled or not")
      val certPathOpt = options.addOption("tls.cert.path", "", "path to cert if TLS is turned on")
      val keyPathOpt = options.addOption("tls.key.path", "", "path to key if TLS turned on")
      val hostNameOpt = options.addOption("hostname", "0.0.0.0", "interface to bind the service to")
      val doormanOpt = options.addOption("doorman", "true", "enable doorman protocol")
      val certmanOpt = options.addOption("certman", "true", "enable certman protocol so that nodes can authenticate using a signed TLS cert")
      val pkixOpt = options.addOption("pkix", "false", "enables certman's pkix validation against JDK default truststore")
      if (args.contains("--help")) {
        options.printOptions()
        return
      }

      val port = portOpt.intValue
      val dbDirectory = dbDirectoryOpt.stringValue.toFile()
      val cacheTimeout = Duration.parse("PT${cacheTimeoutOpt.stringValue}")
      val paramUpdateDelay = Duration.parse("PT${paramUpdateDelayOpt.stringValue}")
      val networkMapUpdateDelay = Duration.parse("PT${networkMapUpdateDelayOpt.stringValue}")
      val tls = tlsOpt.booleanValue
      val certPath = certPathOpt.stringValue
      val keyPath = keyPathOpt.stringValue
      val user = InMemoryUser.createUser("System Admin", usernameOpt.stringValue, passwordOpt.stringValue)
      val enableDoorman = doormanOpt.booleanValue
      val enableCertman = certmanOpt.booleanValue
      val pkix = pkixOpt.booleanValue

      NetworkMapService(
        dbDirectory = dbDirectory,
        user = user,
        port = port,
        cacheTimeout = cacheTimeout,
        networkParamUpdateDelay = paramUpdateDelay,
        networkMapQueuedUpdateDelay = networkMapUpdateDelay,
        tls = tls,
        certPath = certPath,
        keyPath = keyPath,
        hostname = hostNameOpt.stringValue,
        enableCertman = enableCertman,
        enableDoorman = enableDoorman,
        enablePKIValidation = pkix
      ).start().setHandler {
        if (it.failed()) {
          logger.error("failed to complete setup", it.cause())
        } else {
          logger.info("started")
        }
      }
    }
  }
}
