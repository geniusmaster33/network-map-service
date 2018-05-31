/*
 * Copyright 2018 Royal Bank of Scotland
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

package io.cordite.networkmap.serialisation

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import net.corda.core.identity.CordaX500Name

class CordaX500NameSerializer : StdSerializer<CordaX500Name>(CordaX500Name::class.java) {
  override fun serialize(value: CordaX500Name, generator: JsonGenerator, provider: SerializerProvider) {
    generator.writeString(value.toString())
  }
}

class CordaX500NameDeserializer : StdDeserializer<CordaX500Name>(CordaX500Name::class.java) {
  override fun deserialize(parser: JsonParser, context: DeserializationContext): CordaX500Name {
    return CordaX500Name.parse(parser.text)
  }
}