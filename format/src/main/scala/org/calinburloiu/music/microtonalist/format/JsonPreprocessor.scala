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

class JsonPreprocessor(refLoaders: Seq[JsonPreprocessorRefLoader]) {

  import JsonPreprocessor._

  def preprocess(json: JsValue, baseUri: Option[URI]): JsValue =
    if (refLoaders.isEmpty) json else preprocessValue(json, JsPath(), baseUri).value

  /**
   * @param json JSON value to preprocess
   * @param path path where the value was found
   * @return [[Unchanged]] with the unchanged value or [[Changed]] with the preprocessed value
   */
  private def preprocessValue(json: JsValue, path: JsPath, baseUri: Option[URI]): PreprocessResult[JsValue] = {
    json match {
      case obj: JsObject => preprocessObject(obj, path, baseUri)
      case array: JsArray => preprocessArray(array, path, baseUri)
      case other => Unchanged(other)
    }
  }

  /**
   * @param obj  JSON object to preprocess
   * @param path path where the object was found
   * @return [[Unchanged]] with the unchanged object or [[Changed]] with the preprocessed object
   */
  private def preprocessObject(obj: JsObject, path: JsPath, baseUri: Option[URI]): PreprocessResult[JsObject] = {
    val preprocessedMap = obj.value.flatMap {
      case (`RefProp`, JsString(_)) => None
      case (prop, value) => Some((prop, preprocessValue(value, path \ prop, baseUri)))
    }
    lazy val preprocessedObj = JsObject(preprocessedMap.view.mapValues(_.value).toMap)

    val maybeRefUri = obj.value.get(RefProp) match {
      case Some(JsString(uri)) => Some(uri)
      case _ => None
    }

    maybeRefUri match {
      case Some(uriString) =>
        // We have a reference inside this object
        val uri = new URI(uriString)
        val resolvedUri = baseUri.map(_.resolve(uri)).getOrElse(uri)
        val loadedJson = loadRef(resolvedUri, path)
          .getOrElse(throw new JsonPreprocessorRefLoadException(uri, path, "No loader matched the reference!"))
        Changed(preprocessedObj ++ loadedJson)
      case None =>
        // There is no reference inside this object.
        // Was there one deeper inside this object properties?
        val changed = preprocessedMap.values.exists(_.wasChanged)
        if (changed) Changed(preprocessedObj) else Unchanged(obj)
    }
  }

  /**
   * @param array JSON array to preprocess
   * @param path  path where the array was found
   * @return [[Unchanged]] with the unchanged array or [[Changed]] with the preprocessed array
   */
  private def preprocessArray(array: JsArray, path: JsPath, baseUri: Option[URI]): PreprocessResult[JsArray] = {
    val resultItems = array.value.zipWithIndex.map {
      case (value, i) => preprocessValue(value, path \ i, baseUri)
    }
    val changed = resultItems.exists(_.wasChanged)
    if (changed) Changed(JsArray(resultItems.map(_.value))) else Unchanged(array)
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

  private sealed trait PreprocessResult[+A <: JsValue] {
    val value: A

    def wasChanged: Boolean
  }

  private case class Unchanged[+A <: JsValue](override val value: A) extends PreprocessResult[A] {
    override def wasChanged: Boolean = false
  }

  private case class Changed[+A <: JsValue](override val value: A) extends PreprocessResult[A] {
    override def wasChanged: Boolean = true
  }
}

object NoJsonPreprocessor extends JsonPreprocessor(Seq.empty)

trait JsonPreprocessorRefLoader {
  /**
   * Attempts to load a JSON reference by using the given URI found in the given path in the input JSON.
   *
   * @param uri         URL used for loading the reference
   * @param pathContext path in the input JSON where the reference should be replaced
   * @throws JsonPreprocessorRefLoadException if this loader matched the given `uri` and `pathContext`, but an error
   *                                          occurred
   *                                          while loading the reference
   * @return `Some` JSON object to be replaced in the input JSON if this loader matched the given `uri` and
   *         `pathContext`, or None if it did not
   */
  @throws[JsonPreprocessorRefLoadException]
  def load(uri: URI, pathContext: JsPath): Option[JsObject]
}

class JsonPreprocessorRefLoadException(val uri: URI, val pathContext: JsPath, message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
