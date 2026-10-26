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

package org.calinburloiu.music.microtonalist.testbench.driver

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest
import play.api.libs.json.{JsObject, Json}

/**
 * Carries out the server's requests in the app under test.
 *
 * @param fx runs the work on the JavaFX Application Thread
 */
final class RequestHandler(fx: FxExecutor) {
  /**
   * Carries out a request and returns its result, as [[DriverRequest]] describes it for each request.
   *
   * @throws DriverException if the request fails or is not supported
   */
  def handle(request: DriverRequest): JsObject = request match {
    case DriverRequest.Idle =>
      fx.idle()
      Json.obj()
    case other => throw DriverException(s"unsupported request: $other")
  }
}
