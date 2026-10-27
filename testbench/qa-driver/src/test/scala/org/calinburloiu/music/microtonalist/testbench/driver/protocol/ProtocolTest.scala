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

package org.calinburloiu.music.microtonalist.testbench.driver.protocol

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.*
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.MouseButtonName.Secondary
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

class ProtocolTest extends AnyWordSpec with Matchers {

  "Protocol" should {
    "round-trip every request in an envelope" in {
      // Given
      val requests = Seq(Windows, Dump(Some(1)), Snapshot(None, Some("Play"), 2.0),
        Click("Play", Some(0), Secondary, 2),
        Key("Shortcut+N"), Type("é\nx"), Idle, ReadClipboard, WriteClipboard("x"))

      for (request <- requests) {
        // When
        val decoded = Protocol.decodeEnvelope(Protocol.encode(Envelope(7, request)))

        // Then
        decoded shouldBe Right(Envelope(7, request))
      }
    }

    "write each message on one line" in {
      // When / Then
      Protocol.encode(Envelope(1, Type("a\nb"))) should not include "\n"
      Protocol.encode(DriverResponse.Failed(1, "a\nb")) should not include "\n"
    }

    "answer an unknown request with its id" in {
      // When
      val decoded = Protocol.decodeEnvelope("""{"id":4,"op":"explode"}""")

      // Then
      decoded.left.map(_.id) shouldBe Left(4L)
      decoded.left.map(_.message) shouldBe Left("unreadable request: unknown op explode")
    }

    "answer an invalid request with its id" in {
      // When
      val decoded = Protocol.decodeEnvelope("""{"id":5,"op":"click","selector":"Play","button":"Middle"}""")

      // Then
      decoded.left.map(_.id) shouldBe Left(5L)
    }

    "answer an unreadable line with id -1" in {
      // When / Then
      Protocol.decodeEnvelope("not JSON").left.map(_.id) shouldBe Left(-1L)
      Protocol.decodeEnvelope("""{"op":"idle"}""").left.map(_.id) shouldBe Left(-1L)
    }

    "round-trip the responses and the hello" in {
      // Given
      val ok = DriverResponse.Ok(1, Json.obj("text" -> "x"))
      val failed = DriverResponse.Failed(2, "boom")
      val hello = Hello(42, GuiPlatform.Headless)

      // When / Then
      Protocol.decodeResponse(Protocol.encode(ok)) shouldBe Right(ok)
      Protocol.decodeResponse(Protocol.encode(failed)) shouldBe Right(failed)
      Protocol.decodeHello(Protocol.encode(hello)) shouldBe Right(hello)
    }

    "refuse an unreadable response or hello" in {
      // When / Then
      Protocol.decodeResponse("""{"id":1}""") shouldBe a[Left[?, ?]]
      Protocol.decodeResponse("not JSON") shouldBe a[Left[?, ?]]
      Protocol.decodeHello("""{"pid":42,"platform":"Wayland"}""") shouldBe a[Left[?, ?]]
    }
  }
}
