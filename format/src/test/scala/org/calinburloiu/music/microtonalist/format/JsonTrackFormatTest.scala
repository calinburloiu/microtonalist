/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import com.fasterxml.jackson.core.JsonParseException
import org.calinburloiu.music.microtonalist.format.FormatTestUtils.readTracksFromResources
import org.calinburloiu.music.microtonalist.tuner.TrackRepo
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonTrackFormatTest extends AnyFlatSpec with Matchers {
  private val trackFormat: TrackFormat = new JsonTrackFormat(NoJsonPreprocessor)
  private val trackRepo: TrackRepo = {
    val fileTrackRepo = new FileTrackRepo(trackFormat)

    new DefaultTrackRepo(Some(fileTrackRepo), None, None)
  }

  it should "read a basic tracks file" in {
    val tracks = readTracksFromResources("format/tracks/basic.mtlist.tracks", trackRepo)

    // TODO #64 Add assertions
    tracks
  }

  it should "fail to read a file that is not a JSON" in assertThrows[JsonParseException] {
    readTracksFromResources("format/scales/chromatic.scl", trackRepo)
  }

  it should "fail to read a JSON file with another format" in assertThrows[InvalidTrackFormatException] {
    readTracksFromResources("format/scales/minor-just.jscl", trackRepo)
  }
}
