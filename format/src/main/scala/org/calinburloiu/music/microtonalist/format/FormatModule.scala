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

class FormatModule(microtonalistLibraryUri: URI) {
  lazy val huygensFokkerScalaScaleFormat: ScaleFormat = new HuygensFokkerScalaScaleFormat
  lazy val jsonScaleFormat: ScaleFormat = new JsonScaleFormat

  lazy val scaleFormatRegistry: ScaleFormatRegistry = new ScaleFormatRegistry(
    Seq(huygensFokkerScalaScaleFormat, jsonScaleFormat))

  lazy val fileScaleRepo: FileScaleRepo = new FileScaleRepo(scaleFormatRegistry)
  lazy val httpScaleRepo: HttpScaleRepo = new HttpScaleRepo(scaleFormatRegistry)
  lazy val microtonalistLibraryScaleRepo: MicrotonalistLibraryScaleRepo = new MicrotonalistLibraryScaleRepo(
    microtonalistLibraryUri, fileScaleRepo, httpScaleRepo)

  lazy val defaultScaleRepo: ScaleRepo = new DefaultScaleRepo(
    fileScaleRepo, httpScaleRepo, microtonalistLibraryScaleRepo)

  lazy val scaleListFormat: ScaleListFormat = new JsonScaleListFormat(defaultScaleRepo)

  lazy val fileScaleListRepo: FileScaleListRepo = new FileScaleListRepo(scaleListFormat)
  lazy val httpScaleListRepo: HttpScaleListRepo = new HttpScaleListRepo(scaleListFormat)

  lazy val defaultScaleListRepo: ScaleListRepo = new DefaultScaleListRepo(fileScaleListRepo, httpScaleListRepo)
}
