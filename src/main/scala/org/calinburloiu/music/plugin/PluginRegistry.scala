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

package org.calinburloiu.music.plugin

import scala.util.Try
import scala.util.control.NonFatal

trait PluginRegistry[P <: Plugin] {

  def registeredPluginFactories: Seq[PluginFactory[P]]

  protected val indexedFactories: Map[String, PluginFactory[P]] = {
    val indexedById = registeredPluginFactories.map { factory =>
      (factory.pluginId, factory)
    }.toMap
    val indexedByClassName = registeredPluginFactories.map { factory =>
      (factory.getClass.getName, factory)
    }.toMap

    indexedById ++ indexedByClassName
  }

  @throws(classOf[PluginLocatorException])
  def get(id: String): PluginFactory[P] = {
    val maybeFactory = indexedFactories.get(id)
    maybeFactory.getOrElse {
      getByClassName(id)
    }
  }

  private[this] def getByClassName(fqn: String): PluginFactory[P] = {
    Try {
      val clazz = Class.forName(fqn)
      clazz.newInstance() match {
        case plugin: PluginFactory[P] => plugin
        case _ => throw new PluginLocatorException(
          s"Plugin with ID $fqn does not implement the required interface")
      }
    }.recover {
      case e: ClassNotFoundException => throw new PluginLocatorException(
        s"Plugin with ID $fqn was not found", e)
      case e: NoSuchMethodException => throw new PluginLocatorException(
        s"The config for plugin with ID $fqn is invalid", e)
      case NonFatal(t) => throw new PluginLocatorException(
        s"Cannot instantiate plugin with ID $fqn", t)
    }.get
  }
}

class PluginLocatorException(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
