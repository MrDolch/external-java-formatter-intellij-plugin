# external-java-format-intellij-plugin

![Build](https://github.com/MrDolch/external-java-format-intellij-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/23827.svg)](https://plugins.jetbrains.com/plugin/23827)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/23827.svg)](https://plugins.jetbrains.com/plugin/23827)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=MrDolch_external-java-formatter-intellij-plugin&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=MrDolch_external-java-formatter-intellij-plugin)

<!-- Plugin description -->
<p>This plugin enables the integration of any Java formatter into the project.</p>
<p>Unlike other formatters, the SDK of the project is used instead of the Intellij VM when the formatter is called.
   This solves the problem where formatters could not format code from a newer SDK.</p>
<p>The configuration panel allows you to select the main class and specify further parameters.</p>
<p>The <a href="https://github.com/MrDolch/configurable-java-format">configurable-java-format</a> formatter, based on
   the google-java-format style but with a line length of 120 characters, is set as the default setting.</p>
<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "
  external-java-format-intellij-plugin"</kbd> >
  <kbd>Install Plugin</kbd>

- Manually:

  Download the [latest release](https://github.com/MrDolch/external-java-format-intellij-plugin/releases/latest) and
  install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template