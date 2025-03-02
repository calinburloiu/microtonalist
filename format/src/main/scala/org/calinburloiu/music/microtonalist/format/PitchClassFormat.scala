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

import org.calinburloiu.music.scmidi.PitchClass
import play.api.libs.functional.syntax.toApplicativeOps
import play.api.libs.json.*

import scala.util.Try

object PitchClassFormat extends Format[PitchClass] {

  val InvalidPitchClassError: String = "error.pitchClass.invalid"

  override def reads(json: JsValue): JsResult[PitchClass] = json match {
    case _: JsNumber => JsPath.read[Int](Reads.min(0) keepAnd Reads.max(11))
      .map(PitchClass.fromNumber)
      .reads(json)
    case _: JsString => JsPath.read[String]
      .flatMapResult { string =>
        PitchClass.fromName(string).flatMap { pc => Try(pc.assertValid()).toOption } match {
          case Some(pitchClass) => JsSuccess(pitchClass)
          case None => JsError(InvalidPitchClassError)
        }
      }.reads(json)
    case _ => JsError(InvalidPitchClassError)
  }

  override def writes(pitchClass: PitchClass): JsValue = JsNumber(pitchClass.number)
}
