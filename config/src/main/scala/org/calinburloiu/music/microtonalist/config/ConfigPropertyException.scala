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

package org.calinburloiu.music.microtonalist.config

/**
 * Exception thrown when an error occurred while deserializing a config property.
 *
 * @param propertyPath HOCON config property path (e.g. `"core.libraryBaseUrl"`)
 * @param propertyRequirementMessage message that describes the requirement for the above property (e.g. "must be a
 *                                   valid URI or file system path")
 */
class ConfigPropertyException(propertyPath: String, propertyRequirementMessage: String, cause: Throwable = null)
  extends ConfigException(s"$propertyPath $propertyRequirementMessage", cause)
