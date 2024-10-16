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

import java.net.URI
import java.net.http.HttpClient
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class FormatModule(libraryUri: URI,
                   synchronousAwaitTimeout: FiniteDuration = 1 minute) {
  lazy val jsonPreprocessor: JsonPreprocessor = new JsonPreprocessor(
    Seq(jsonPreprocessorFileRefLoader, jsonPreprocessorHttpRefLoader)
  )

  lazy val httpClient: HttpClient = HttpClient.newHttpClient()

  lazy val jsonPreprocessorFileRefLoader: JsonPreprocessorFileRefLoader = new JsonPreprocessorFileRefLoader
  lazy val jsonPreprocessorHttpRefLoader: JsonPreprocessorHttpRefLoader = new JsonPreprocessorHttpRefLoader(httpClient)

  lazy val huygensFokkerScalaScaleFormat: ScaleFormat = new HuygensFokkerScalaScaleFormat
  lazy val jsonScaleFormat: JsonScaleFormat = new JsonScaleFormat(jsonPreprocessor)

  lazy val scaleFormatRegistry: ScaleFormatRegistry = new ScaleFormatRegistry(
    Seq(huygensFokkerScalaScaleFormat, jsonScaleFormat))

  lazy val fileScaleRepo: FileScaleRepo = new FileScaleRepo(scaleFormatRegistry)
  lazy val httpScaleRepo: HttpScaleRepo = new HttpScaleRepo(httpClient, scaleFormatRegistry)
  lazy val microtonalistLibraryScaleRepo: LibraryScaleRepo = new LibraryScaleRepo(
    libraryUri, fileScaleRepo, httpScaleRepo)

  lazy val defaultScaleRepo: ScaleRepo = new DefaultScaleRepo(
    Some(fileScaleRepo), Some(httpScaleRepo), Some(microtonalistLibraryScaleRepo))

  lazy val compositionFormat: CompositionFormat = new JsonCompositionFormat(
    defaultScaleRepo, jsonPreprocessor, jsonScaleFormat, synchronousAwaitTimeout)

  lazy val fileCompositionRepo: FileCompositionRepo = new FileCompositionRepo(compositionFormat,
    synchronousAwaitTimeout)
  lazy val httpCompositionRepo: HttpCompositionRepo = new HttpCompositionRepo(
    httpClient, compositionFormat, synchronousAwaitTimeout)

  lazy val defaultCompositionRepo: CompositionRepo = new DefaultCompositionRepo(fileCompositionRepo,
    httpCompositionRepo)
}
