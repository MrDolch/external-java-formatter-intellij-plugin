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
    var classPath: String? = "configurable-google-java-format-2024.21.0-all-deps.jar"
    var mainClass: String? = "com.google.googlejavaformat.java.Main"
    var arguments: String? = "--width=120 {}"
    var workingDir: String? = Path.of(
        PathManager.getPluginsPath(), "external-java-formatter-intellij-plugin", "lib"
    ).toString()
    var vmOptions: String? = """
            --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED,com.google.googlejavaformat
            --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED,com.google.googlejavaformat
            --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED,com.google.googlejavaformat
            --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED,com.google.googlejavaformat
            --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED,com.google.googlejavaformat
            --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED,com.google.googlejavaformat
        """.trimIndent()
    var testCode: String? = """
            package tech.dolch.formatting;
            
            import static java.util.function.Function.identity;
            import static java.util.stream.Collectors.toUnmodifiableMap;
            
            import java.util.Arrays;
            import java.util.Map;
            
            public class TestFormatting {
            
              private static final int[] INTS = {0, 2, 3};
            
              private enum State {
                READY,
                PROGRESSING,
                DONE
              }
            
              public int[] topKFrequent(final int[] nums, int k) {
                return Arrays.stream(nums).boxed().collect(toUnmodifiableMap(identity(), v -> 1, Integer::sum)).entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .mapToInt(Map.Entry::getKey)
                    .limit(k)
                    .toArray();
              }
            }
    """.trimIndent()
  }
}