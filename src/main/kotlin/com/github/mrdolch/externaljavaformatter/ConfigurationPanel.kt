package com.github.mrdolch.externaljavaformatter

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.options.BaseConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.uiDesigner.core.GridConstraints
import com.intellij.uiDesigner.core.GridLayoutManager
import com.intellij.uiDesigner.core.Spacer
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nls
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.*
import kotlin.math.max


internal class ConfigurationPanel(private val project: Project) : BaseConfigurable(), SearchableConfigurable {
  private val panel: JPanel = JPanel()
  private val enabled: JCheckBox = JCheckBox("Enable external-java-formatter")
  private val sendContent: JCheckBox = JCheckBox("Send content to Formatter over Standard-In")
  private val classPath: JTextField = JTextField()
  private val mainClass: JTextField = JTextField()
  private val arguments: JTextField = JTextField()
  private val workingDir: JTextField = JTextField()
  private val vmOptions: JTextArea = JTextArea()
  private val testCode: JTextArea = JTextArea()
  private val testButton: JButton = JButton("Test")

  override fun getId(): String {
    return "external-java-formatter.settings"
  }

  override fun getDisplayName(): String {
    return "External-java-formatter Settings"
  }

  override fun apply() {
    val configuration = project.getService(PersistConfigurationService::class.java).state
    configuration.enabled = enabled.isSelected
    configuration.sendContent = sendContent.isSelected
    configuration.classPath = classPath.text
    configuration.mainClass = mainClass.text
    configuration.arguments = arguments.text
    configuration.workingDir = workingDir.text
    configuration.vmOptions = vmOptions.text
    configuration.testCode = testCode.text
  }

  override fun reset() {
    val configuration = project.getService(PersistConfigurationService::class.java).state
    enabled.isSelected = configuration.enabled ?: false
    sendContent.isSelected = configuration.sendContent ?: false
    classPath.text = configuration.classPath
    mainClass.text = configuration.mainClass
    arguments.text = configuration.arguments
    workingDir.text = configuration.workingDir
    vmOptions.text = configuration.vmOptions
    vmOptions.caretPosition = 0
    testCode.text = configuration.testCode
    testCode.caretPosition = 0
  }

  override fun isModified(): Boolean {
    val configuration = project.getService(PersistConfigurationService::class.java).state
    return enabled.isSelected != configuration.enabled
        || sendContent.isSelected != configuration.sendContent
        || classPath.text != configuration.classPath
        || mainClass.text != configuration.mainClass
        || arguments.text != configuration.arguments
        || workingDir.text != configuration.workingDir
        || vmOptions.text != configuration.vmOptions
        || testCode.text != configuration.testCode
  }

  override fun createComponent(): JComponent {

    val monospacedFont = Font(Font.MONOSPACED, Font.PLAIN, workingDir.font.size)
    vmOptions.setFont(monospacedFont)
    testCode.setFont(monospacedFont)
    testButton.addActionListener { formatTestCode() }

    val vmOptionsPane = JScrollPane(vmOptions, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS)
    vmOptionsPane.preferredSize = Dimension(mainClass.preferredSize.width, 150)
    val testCodePane = JScrollPane(testCode, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS)
    testCodePane.preferredSize = Dimension(mainClass.preferredSize.width, 200)
    workingDir.preferredSize = Dimension(mainClass.preferredSize.width, workingDir.preferredSize.height)

    panel.layout = GridLayoutManager(9, 2, JBUI.emptyInsets(), -1, -1)
    panel.add(
        enabled, GridConstraints(
        0, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
        GridConstraints.SIZEPOLICY_CAN_SHRINK or GridConstraints.SIZEPOLICY_CAN_GROW,
        GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false
    )
    )
    panel.add(JLabel("Class Path"), left(1))
    panel.add(classPath, right(1))
    panel.add(JLabel("Main Class"), left(2))
    panel.add(mainClass, right(2))
    panel.add(JLabel("Arguments"), left(3))
    panel.add(arguments, right(3))
    panel.add(JLabel("Working Dir"), left(4))
    panel.add(workingDir, right(4))
    panel.add(JLabel("VM-Options"), left(5))
    panel.add(vmOptionsPane, right(5, Dimension(50, 50)))
    panel.add(JLabel("Test-Code"), left(6))
    panel.add(testCodePane, right(6, Dimension(50, 50)))
    panel.add(testButton, left(7))
    panel.add(JLabel(""), left(7))
    panel.add(
        Spacer(), GridConstraints(
        8, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL,
        1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false
    )
    )
    return panel
  }

  private fun formatTestCode() {
    if (!enabled.isSelected) {
      JOptionPane.showMessageDialog(testButton, "Formatter must be enabled via the checkbox.",
          "Formatting could not be started", JOptionPane.ERROR_MESSAGE)
      return
    }
    val projectSdk: Sdk? = ProjectRootManager.getInstance(project).projectSdk
    if (projectSdk == null) {
      JOptionPane.showMessageDialog(testButton, "A project SDK must be selected under Project settings.",
          "Formatting could not be started", JOptionPane.ERROR_MESSAGE)
      return
    }
    val tempFile = File.createTempFile("external-java-format-test", ".java")
    tempFile.deleteOnExit()
    tempFile.writeText(testCode.text)
    val commandLine = FormattingRequestExecutor.createCommandLine(
        tempFile, projectSdk, workingDir.text, mainClass.text, classPath.text, arguments.text, vmOptions.text)
    var stdErr = "";
    var exitCode = 0;
    val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
        {
          val capturingProcessHandler = CapturingProcessHandler(commandLine)
          capturingProcessHandler.runProcess(timeoutInSeconds * 1000)
              .let {
                if (it.exitCode == 0) {
                  val firstDiffPos = findFirstDiffPos(testCode.text, it.stdout)
                  testCode.text = it.stdout
                  testCode.caretPosition = max(firstDiffPos, 0)
                  testCode.requestFocus()
                }
                stdErr = it.stderr
                exitCode = it.exitCode
              }
        },
        "Test formatting ...", true, project
    )
    if (completed) {
      if (exitCode == 0) JOptionPane.showMessageDialog(testButton, stdErr, "Formatting was successfully completed", JOptionPane.INFORMATION_MESSAGE)
      else JOptionPane.showMessageDialog(testButton, stdErr, "Formatting was terminated with an error", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun findFirstDiffPos(a: String, b: String): Int {
    var i = 0
    if (a == b) return -1
    while (a[i] == b[i]) i++
    return i
  }

  private fun left(row: Int) = GridConstraints(
      row, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
      GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false
  )

  private fun right(row: Int, minimumSize: Dimension? = null) = GridConstraints(
      row, 1, 1, 1,
      GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW,
      GridConstraints.SIZEPOLICY_CAN_GROW, minimumSize, null, null, 0, false
  )
}