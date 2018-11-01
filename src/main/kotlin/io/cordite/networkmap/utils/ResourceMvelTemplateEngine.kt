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
package io.cordite.networkmap.utils

import io.vertx.ext.web.RoutingContext
import org.mvel2.integration.impl.ImmutableDefaultFactory
import org.mvel2.templates.TemplateCompiler
import org.mvel2.templates.TemplateRuntime
import org.mvel2.util.StringAppender
import javax.ws.rs.core.HttpHeaders

/**
 * vertx-web comes with a number of template engines
 * Unfortunately, these always process files from the filesystem,
 * rather than being capable to process resource files, like StaticHandler.
 * This class allows the processing of resource files
 */
class ResourceMvelTemplateEngine(
  val cachingEnabled: Boolean,
  private val properties: Map<String, String>,
  private val rootPath: String,
  private val fileSuffixWhitelist: List<String> = listOf("html")
) {
  val cache = mutableMapOf<String, String>()

  private fun resolvePath(path: String) : String {
    return if (cachingEnabled) {
      cache.computeIfAbsent(path, this::resolvePathUncached)
    } else {
      resolvePathUncached(path)
    }
  }

  private fun resolvePathUncached(path: String) : String {
    val text = ClassLoader.getSystemClassLoader().getResource(rootPath + path).readText()
    val template= TemplateCompiler.compileTemplate(text)
    return TemplateRuntime(template.template, null, template.root, ".").execute(StringAppender(), properties, ImmutableDefaultFactory()).toString()
  }

  fun handler(context: RoutingContext, rootPath: String) {
    try {
      val path = context.request().path().let {
        if (it.startsWith(rootPath)) {
          it.drop(rootPath.length)
        } else {
          it
        }.dropLastWhile { it == '/' }
      }.let {
        if (it.isEmpty()) {
          "index.html"
        } else {
          it
        }
      }
      val process = fileSuffixWhitelist.any { path.endsWith(".$it") }
      if (process) {
        val result = resolvePath(path)
        context.response().putHeader(HttpHeaders.CONTENT_LENGTH, result.length.toString()).end(result)
      } else {
        context.next()
      }
    } catch (err: Throwable) {
      context.end(err)
    }
  }
}