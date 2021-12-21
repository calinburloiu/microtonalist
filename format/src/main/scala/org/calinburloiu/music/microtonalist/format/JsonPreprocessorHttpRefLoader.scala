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
import play.api.libs.json.{JsObject, JsPath}

import java.net.URI

/**
 * Loader for JSON preprocessor references that retrieves the referenced URIs via HTTP without
 * performing any validation and checking for the path context.
 */
class JsonPreprocessorHttpRefLoader extends JsonPreprocessorRefLoader {
  override def load(uri: URI, pathContext: JsPath): Option[JsObject] = {
    require(uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme))

    ???
  }
}
