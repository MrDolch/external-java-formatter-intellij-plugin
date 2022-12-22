package com.github.mrdolch.externaljavaformatter

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path

@State(name = "ExternalJavaFormatterSettings", storages = [Storage("external-java-formatter.xml")])
class PersistConfigurationService : PersistentStateComponent<PersistConfigurationService.Configuration> {
  private var configuration = Configuration()
  override fun getState(): Configuration = configuration
  override fun loadState(configuration: Configuration) {
    this.configuration = configuration
  }

  class Configuration {
    var enabled: Boolean? = false
    var sendContent: Boolean? = null // TODO implement sending content via stdin to formatter
    var classPath: String? = "configurable-google-java-format-1.15.0.1-all-deps.jar"
    var mainClass: String? = "com.google.googlejavaformat.java.Main"
    var arguments: String? = "--width=120 {}"
    var workingDir: String? = Path.of(
      PathManager.getPluginsPath(), "external-java-formatter-intellij-plugin", "lib"
    ).toString()
    var vmOptions: String? = """
            --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
            --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
            --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
            --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
        """.trimIndent()
    var testCode: String? = ""
  }
}