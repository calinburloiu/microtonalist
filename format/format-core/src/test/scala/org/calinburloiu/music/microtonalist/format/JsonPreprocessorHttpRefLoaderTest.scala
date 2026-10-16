/*
 * Copyright 2026 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.calinburloiu.music.microtonalist.format

import com.sun.net.httpserver.HttpServer
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.JsPath

import java.net.http.HttpClient
import java.net.{InetSocketAddress, URI}

class JsonPreprocessorHttpRefLoaderTest extends AnyWordSpec with Matchers with MockFactory {

  private trait Fixture {
    // A mock without expectations: a request sent through it fails the test.
    val loader: JsonPreprocessorHttpRefLoader = JsonPreprocessorHttpRefLoader(mock[HttpClient])
  }

  "load" should {
    "leave a file URI to another loader, without sending a request" in new Fixture {
      // When / Then
      loader.load(URI("file:///Users/john/Music/scale.json"), JsPath) shouldBe None
    }

    "leave a relative URI to another loader, without sending a request" in new Fixture {
      // When / Then
      loader.load(URI("scales/scale.json"), JsPath) shouldBe None
    }

    "throw a JsonPreprocessorRefLoadException when the URL is not found (404)" in {
      // Given
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/",
        exchange => {
          exchange.sendResponseHeaders(404, -1)
          exchange.close()
        })
      server.start()
      val uri = URI(s"http://127.0.0.1:${server.getAddress.getPort}/refs/missing.json")
      val pathContext = JsPath \ "tuningReference"
      val loader = JsonPreprocessorHttpRefLoader(HttpClient.newHttpClient())

      try {
        // When
        val exception = intercept[JsonPreprocessorRefLoadException] {
          loader.load(uri, pathContext)
        }

        // Then
        exception.uri shouldEqual uri
        exception.pathContext shouldEqual pathContext
      } finally {
        server.stop(0)
      }
    }
  }
}
