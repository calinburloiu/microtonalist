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

import com.google.common.net.MediaType

/**
 * Scale format metadata.
 *
 * @param name       name of the scale format
 * @param extensions file extensions (without the dot) used to the format (if any)
 * @param mediaTypes specific media types (MIME types) used for the format (if any)
 */
case class ScaleFormatMetadata(name: String,
                               extensions: Set[String],
                               mediaTypes: Set[MediaType] = Set.empty)
