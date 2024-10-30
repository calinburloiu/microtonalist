/*
 * Copyright 2024 Calin-Andrei Burloiu
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

/**
 * Factory that creates a [[JsonFormatComponent]] instance.
 *
 * @tparam A type of the component.
 */
trait JsonFormatComponentFactory[A] {

  val familyName: String

  val specs: JsonFormatComponent.TypeSpecs[A]

  val defaultTypeName: Option[String]

  lazy val jsonFormatComponent: JsonFormatComponent[A] = {
    require(defaultTypeName.forall { typeName => specs.exists(_.typeName == typeName) },
      "defaultTypeName must exist in specs!")

    new JsonFormatComponent[A](familyName, specs, defaultTypeName)
  }
}
