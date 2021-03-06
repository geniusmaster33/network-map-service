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
package io.cordite.networkmap.storage.mongo

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.reactivestreams.client.MongoClient
import io.bluebank.braid.core.logging.loggerFor
import io.cordite.networkmap.storage.file.TextStorage
import io.cordite.networkmap.storage.mongo.serlalisation.BsonId
import io.cordite.networkmap.utils.all
import io.cordite.networkmap.utils.mapUnit
import io.cordite.networkmap.utils.onSuccess
import io.vertx.core.Future

class MongoTextStorage(mongoClient: MongoClient,
                       database: String = MongoStorage.DEFAULT_DATABASE,
                       collection: String = "etc") {
  companion object {
    private val log = loggerFor<MongoTextStorage>()
  }

  private val collection = mongoClient.getDatabase(database).getTypedCollection<KeyValue>(collection)

  fun clear(): Future<Unit> = collection.drop().toFuture().mapUnit()

  fun put(key: String, value: String): Future<Unit> = collection
    .replaceOne(KeyValue::key eq key, KeyValue(key, value), ReplaceOptions().upsert(true))
    .toFuture().mapUnit()

  fun put(keyValue: KeyValue): Future<Unit> = collection
    .replaceOne(KeyValue::key eq keyValue.key, keyValue, ReplaceOptions().upsert(true))
    .toFuture().mapUnit()

  fun get(key: String): Future<String> = collection.find(KeyValue::key eq key)
    .first()
    .toFuture()
    .map { it.value }

  fun getOrDefault(key: String, default: String): Future<String> = collection.find(KeyValue::key eq key)
    .first()
    .toFuture()
    .map { it.value }
    .recover { Future.succeededFuture(default) }

  fun migrate(textStorage: TextStorage): Future<Unit> {
    return textStorage.getAll()
      .map { it.map { KeyValue(it.key, it.value) } }
      .compose {
        if (it.isEmpty()) {
          log.info("text storage is empty; no migration required")
          Future.succeededFuture(Unit)
        } else {
          log.info("migrating text storage to mongodb")
          it.map {
            log.info("migrating $it")
            put(it)
          }.all()
            .compose {
              log.info("clearing file-base text storage")
              textStorage.clear()
            }
            .onSuccess { log.info("text storage migration done") }
        }
      }
  }

  data class KeyValue(@BsonId val key: String, val value: String)
}