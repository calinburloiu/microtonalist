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
import scala.collection.mutable

/**
 * JSON preprocessor that replaces JSON references to other JSON files with the actual JSON value.
 *
 * A JSON reference is a `$ref` property in a JSON object that has a URI as value. The given `refLoaders` are used
 * to load JSON objects based on that URI and other context information. The `$ref` property is replaced with the
 * object loaded. Properties already present in the object that contained the `$ref` are overridden by those loaded.
 *
 * @param refLoaders a list of reference loaders responsible to load JSON values from reference URI based on context
 */
class JsonPreprocessor(refLoaders: Seq[JsonPreprocessorRefLoader]) {

  import JsonPreprocessor._

  /**
   * Performs preprocessing in the given JSON value.
   *
   * @param json    JSON value to be preprocessed
   * @param baseUri optional base URI used to resolve relative URIs from `$ref` properties
   * @return a preprocessed JSON with all references replaced
   */
  def preprocess(json: JsValue, baseUri: Option[URI]): JsValue =
    if (refLoaders.isEmpty) json else preprocessValue(json, JsPath(), baseUri, new NestedRefContext).value

  /**
   * @param json             JSON value to preprocess
   * @param path             path where the value was found
   * @param baseUri          optional base URI used to resolve relative URIs from `$ref` properties
   * @param nestedRefContext object used for accounting nested references
   * @return [[Unchanged]] with the unchanged value or [[Changed]] with the preprocessed value
   */
  private def preprocessValue(json: JsValue,
                              path: JsPath,
                              baseUri: Option[URI],
                              nestedRefContext: NestedRefContext): PreprocessResult[JsValue] = {
    json match {
      case obj: JsObject => preprocessObject(obj, path, baseUri, nestedRefContext)
      case array: JsArray => preprocessArray(array, path, baseUri, nestedRefContext)
      case other => Unchanged(other)
    }
  }

  /**
   * @param obj              JSON object to preprocess
   * @param path             path where the object was found
   * @param baseUri          optional base URI used to resolve relative URIs from `$ref` properties
   * @param nestedRefContext object used for accounting nested references
   * @return [[Unchanged]] with the unchanged object or [[Changed]] with the preprocessed object
   */
  private def preprocessObject(obj: JsObject,
                               path: JsPath,
                               baseUri: Option[URI],
                               nestedRefContext: NestedRefContext): PreprocessResult[JsObject] = {
    val preprocessedMap = obj.value.flatMap {
      case (`RefProp`, JsString(_)) => None
      case (prop, value) => Some((prop, preprocessValue(value, path \ prop, baseUri, nestedRefContext)))
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
        val loadedJson = loadRef(resolvedUri, path, nestedRefContext)
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
   * @param array            JSON array to preprocess
   * @param path             path where the array was found
   * @param baseUri          optional base URI used to resolve relative URIs from `$ref` properties
   * @param nestedRefContext object used for accounting nested references
   * @return [[Unchanged]] with the unchanged array or [[Changed]] with the preprocessed array
   */
  private def preprocessArray(array: JsArray,
                              path: JsPath,
                              baseUri: Option[URI],
                              nestedRefContext: NestedRefContext): PreprocessResult[JsArray] = {
    val resultItems = array.value.zipWithIndex.map {
      case (value, i) => preprocessValue(value, path \ i, baseUri, nestedRefContext)
    }
    val changed = resultItems.exists(_.wasChanged)
    if (changed) Changed(JsArray(resultItems.map(_.value))) else Unchanged(array)
  }

  /**
   * Tries to load a JSON object from the given URI by taking a JSON path as context.
   *
   * @param uri              URI where the JSON object is to be read
   * @param pathContext      JSON path used as context
   * @param nestedRefContext object used for accounting nested references
   * @return maybe a JSON object
   */
  private def loadRef(uri: URI, pathContext: JsPath, nestedRefContext: NestedRefContext): Option[JsObject] = {
    for (loader <- refLoaders) {
      loader.load(uri, pathContext) match {
        case Some(obj) =>
          nestedRefContext.depth += 1
          if (nestedRefContext.depth == MaxNestedRefDepth) {
            throw new JsonPreprocessorRefLoadException(uri, pathContext,
              "JSON preprocessor encountered nested references that exceed the maximum allowed depth of " +
                MaxNestedRefDepth)
          }

          val normalizedUri = uri.normalize
          if (nestedRefContext.visitedRefs.contains(normalizedUri)) {
            throw new JsonPreprocessorRefLoadException(uri, pathContext,
              s"JSON preprocessor encountered nested references that from a cycle: $uri was already loaded")
          }
          nestedRefContext.visitedRefs += uri.normalize

          val preprocessedObj = preprocessObject(obj, pathContext, Some(baseUriOf(uri)), nestedRefContext).value

          return Some(preprocessedObj)

        case None =>
      }
    }

    None
  }
}

object JsonPreprocessor {
  val RefProp: String = "$ref"

  val MaxNestedRefDepth: Int = 100

  /**
   * Internal intermediary preprocessing result object used for accounting if a subtree was modified by the
   * preprocessor.
   */
  private sealed trait PreprocessResult[+A <: JsValue] {
    val value: A

    def wasChanged: Boolean
  }

  /**
   * Internal intermediary preprocessing result object which tells that a subtree was not modified by the preprocessor.
   */
  private case class Unchanged[+A <: JsValue](override val value: A) extends PreprocessResult[A] {
    override def wasChanged: Boolean = false
  }

  /**
   * Internal intermediary preprocessing result object which tells that a subtree was modified by the preprocessor.
   */
  private case class Changed[+A <: JsValue](override val value: A) extends PreprocessResult[A] {
    override def wasChanged: Boolean = true
  }

  /**
   * Object used for accounting nested references in order to avoid very deep references or cycles.
   */
  private class NestedRefContext {
    var depth: Int = 0
    val visitedRefs: mutable.Set[URI] = mutable.HashSet()
  }
}

/**
 * A special no-op [[JsonPreprocessor]] that performs no preprocessing.
 */
object NoJsonPreprocessor extends JsonPreprocessor(Seq.empty)

/**
 * Trait extended for loading JSON references for [[JsonPreprocessor]].
 */
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

/**
 * Exception thrown when an error occurred loading a JSON reference in [[JsonPreprocessor]].
 *
 * @param uri         URI where the JSON object referenced is to be read
 * @param pathContext JSON path where the reference was encountered
 */
class JsonPreprocessorRefLoadException(val uri: URI, val pathContext: JsPath, message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
