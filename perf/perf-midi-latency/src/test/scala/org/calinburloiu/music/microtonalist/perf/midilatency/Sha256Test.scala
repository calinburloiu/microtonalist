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

package org.calinburloiu.music.microtonalist.perf.midilatency

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class Sha256Test extends AnyWordSpec with Matchers {

  private val AbcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

  "Sha256" should {
    "digest a text" in {
      // When / Then
      Sha256.ofString("abc") shouldEqual AbcDigest
    }

    "digest a file" in {
      // Given
      val file = Files.createTempFile("sha256", ".txt")
      Files.writeString(file, "abc")

      // When / Then
      Sha256.ofFile(file) shouldEqual AbcDigest
    }
  }
}
