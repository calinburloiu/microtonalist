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

package org.calinburloiu.music.microtonalist.testbench.qa

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import scala.util.Random

class QaRunTest extends AnyWordSpec with Matchers {

  private trait Fixture {
    val base: Path = Files.createTempDirectory("qa")
    val run: QaRun = QaRun.create(base)
  }

  /** A random generator whose every draw is the same, so that every ID it gives is the same. */
  private def constantRandom: Random = Random(new java.util.Random {
    override protected def next(bits: Int): Int = 0
  })

  "QaRun.create" should {
    "create a run directory named by a short unique ID" in new Fixture {
      // Then
      run.id should fullyMatch regex "qa-[0-9a-f]{4}"
      Files.isDirectory(base.resolve(run.id)) shouldBe true
      run.dir shouldBe base.resolve(run.id).toRealPath()
    }

    "pick another ID when the directory exists" in new Fixture {
      // Given
      val seed = 397L
      val firstRun: QaRun = QaRun.create(base, Random(seed))

      // When
      val secondRun: QaRun = QaRun.create(base, Random(seed))

      // Then
      secondRun.id should not be firstRun.id
      Files.isDirectory(secondRun.dir) shouldBe true
    }

    "give up when every ID it draws is taken" in new Fixture {
      // Given
      QaRun.create(base, constantRandom)

      // When / Then
      a[QaException] should be thrownBy QaRun.create(base, constantRandom)
    }

    "create a missing base directory" in new Fixture {
      // Given
      val missingBase: Path = base.resolve("m")

      // When
      val newRun: QaRun = QaRun.create(missingBase)

      // Then
      Files.isDirectory(missingBase.resolve(newRun.id)) shouldBe true
    }

    "refuse a base directory whose socket path would be too long" in new Fixture {
      // Given
      val longBase: Path = base.resolve("x" * 120)

      // When / Then
      a[QaException] should be thrownBy QaRun.create(longBase)
      Files.exists(longBase) shouldBe false
    }
  }

  "QaRun.socket" should {
    "be the d.sock file of the run directory" in new Fixture {
      // Then
      run.socket shouldBe run.dir.resolve("d.sock")
    }
  }

  "QaRun.file" should {
    "name a file of the run directory" in new Fixture {
      // Then
      run.file("app.log") shouldBe run.dir.resolve("app.log")
    }
  }

  "QaRun.resolve" should {
    "resolve a relative path inside the run directory" in new Fixture {
      // When / Then
      run.resolve("qa.mtlist") shouldBe run.dir.resolve("qa.mtlist")
    }

    "resolve a path whose parent doesn't exist yet" in new Fixture {
      // When / Then
      run.resolve("sub/qa.mtlist") shouldBe run.dir.resolve("sub").resolve("qa.mtlist")
    }

    "resolve an existing file to its real path" in new Fixture {
      // Given
      Files.writeString(run.dir.resolve("qa.mtlist"), "{}")

      // When / Then
      run.resolve(s"${run.dir}/./qa.mtlist") shouldBe run.dir.resolve("qa.mtlist")
    }

    "accept a path through a symlinked base directory" in new Fixture {
      // Given
      val realBase: Path = base.toRealPath()
      val link: Path = Files.createSymbolicLink(realBase.resolveSibling(s"${realBase.getFileName}-link"), realBase)

      // When
      val linkedRun: QaRun = QaRun.create(link)

      // Then
      linkedRun.dir shouldBe realBase.resolve(linkedRun.id)
      linkedRun.resolve(s"$link/${linkedRun.id}/qa.mtlist") shouldBe linkedRun.dir.resolve("qa.mtlist")
    }

    "refuse a path out of the run directory" in new Fixture {
      // When / Then
      a[QaException] should be thrownBy run.resolve("../x")
      a[QaException] should be thrownBy run.resolve("/etc/hosts")
    }

    "refuse an invalid path" in new Fixture {
      // When / Then
      a[QaException] should be thrownBy run.resolve("qa\u0000.mtlist")
    }

    "refuse a symlink in the run directory that points out of it" in new Fixture {
      // Given
      val outside: Path = Files.createTempDirectory("qa-run-test-outside")
      Files.writeString(outside.resolve("hosts"), "")
      Files.createSymbolicLink(run.dir.resolve("out"), outside)

      // When / Then
      a[QaException] should be thrownBy run.resolve("out")
      a[QaException] should be thrownBy run.resolve("out/hosts")
      a[QaException] should be thrownBy run.resolve("out/missing")
    }

    "refuse a dangling symlink in the run directory" in new Fixture {
      // Given
      val outside: Path = Files.createTempDirectory("qa-run-test-outside")
      Files.createSymbolicLink(run.dir.resolve("dangling"), outside.resolve("missing"))

      // When / Then
      a[QaException] should be thrownBy run.resolve("dangling")
    }
  }
}
