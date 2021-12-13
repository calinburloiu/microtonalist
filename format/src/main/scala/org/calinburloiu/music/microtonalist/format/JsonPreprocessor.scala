/*
 * Copyright 2021 Calin-Andrei Burloiu
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

import play.api.libs.json._

import java.net.URI

class JsonPreprocessor(refLoaders: Seq[JsonRefLoader]) {

  import JsonPreprocessor._

  def preprocess(obj: JsObject): JsObject = preprocessObject(obj, JsPath())

  private def preprocessValue(json: JsValue, path: JsPath): JsValue = {
    json match {
      case obj: JsObject => preprocessObject(obj, path)
      case array: JsArray => preprocessArray(array, path)
      case other => other
    }
  }

  private def preprocessObject(obj: JsObject, path: JsPath): JsObject = {
    val preprocessedMap = obj.value.flatMap {
      case (`RefProp`, JsString(_)) => None
      case (prop, value) => Some((prop, preprocessValue(value, path \ prop)))
    }
    val preprocessedObj = JsObject(preprocessedMap)

    val maybeUri = obj.value.get(RefProp) match {
      case Some(JsString(uri)) => Some(uri)
      case _ => None
    }

    maybeUri match {
      case Some(uriString) =>
        // TODO #38 Use a special exception class
        val uri = new URI(uriString)
        val loadedJson = loadRef(uri, path)
          .getOrElse(throw new JsonRefLoadException(uri, path, "No loaded matched the reference!"))
        preprocessedObj ++ loadedJson
      case None => preprocessedObj
    }
  }

  private def preprocessArray(array: JsArray, path: JsPath): JsArray = {
    val items = array.value.zipWithIndex.map {
      case (value, i) => preprocessValue(value, path \ i)
    }
    JsArray(items)
  }

  private def loadRef(uri: URI, pathContext: JsPath): Option[JsObject] = {
    for (loader <- refLoaders) {
      loader.load(uri, pathContext) match {
        case Some(obj) => return Some(obj)
        case None =>
      }
    }

    None
  }
}

object JsonPreprocessor {
  val RefProp: String = "$ref"
}

trait JsonRefLoader {
  /**
   * Attempts to load a JSON reference by using the given URI found in the given path in the input JSON.
   *
   * @param uri         URL used for loading the reference
   * @param pathContext path in the input JSON where the reference should be replaced
   * @throws JsonRefLoadException if this loader matched the given `uri` and `pathContext`, but an error occurred
   *                              while loading the reference
   * @return `Some` JSON object to be replaced in the input JSON if this loader matched the given `uri` and
   *         `pathContext`, or None if it did not
   */
  @throws[JsonRefLoadException]
  def load(uri: URI, pathContext: JsPath): Option[JsObject]
}

class JsonRefLoadException(val uri: URI, val pathContext: JsPath, message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
