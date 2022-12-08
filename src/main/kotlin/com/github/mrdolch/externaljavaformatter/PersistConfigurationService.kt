/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.mrdolch.externaljavaformatter

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "ExternalJavaFormatterSettings", storages = [Storage("external-java-formatter.xml")])
internal class PersistConfigurationService : PersistentStateComponent<PersistConfigurationService.Configuration> {
    private var configuration = Configuration()
    override fun getState(): Configuration = configuration
    override fun loadState(configuration: Configuration) {
        this.configuration = configuration
    }

    internal class Configuration {
        var enabled: Boolean? = null
        var sendContent: Boolean? = null // TODO implement sending content via stdin to formatter
        var classpath: String? = null
        var mainClass: String? = null
        var arguments: String? = null
        var vmOptions: String? = null
        var testCode: String? = null
    }
}