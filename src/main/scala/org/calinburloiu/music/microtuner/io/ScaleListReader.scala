/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner.io

import java.io.InputStream
import java.nio.file.Path

import org.calinburloiu.music.microtuner.ScaleList

trait ScaleListReader {

  def read(inputStream: InputStream): ScaleList
}

class InvalidScaleListFileException(message: String, cause: Throwable = null) extends Exception(message, cause)
