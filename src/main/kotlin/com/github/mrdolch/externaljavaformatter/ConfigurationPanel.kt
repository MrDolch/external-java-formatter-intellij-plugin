package com.github.mrdolch.externaljavaformatter

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
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
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.*
import kotlin.math.max


internal class ConfigurationPanel(private val project: Project) : BaseConfigurable(), SearchableConfigurable {
  private val panel: JPanel = JPanel()
  private val enabled: JCheckBox = JCheckBox("Enable external-java-formatter")
  private val useStandardIn: JCheckBox =
    JCheckBox("Send content to Formatter over Standard-In. Otherwise, use {} in Arguments to mark where filename is inserted.")
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
    configuration.useStandardIn = useStandardIn.isSelected
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
    useStandardIn.isSelected = configuration.useStandardIn ?: false
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
        || useStandardIn.isSelected != configuration.useStandardIn
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

    val vmOptionsPane =
      JScrollPane(vmOptions, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS)
    vmOptionsPane.preferredSize = Dimension(mainClass.preferredSize.width, 150)
    val testCodePane =
      JScrollPane(testCode, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS)
    testCodePane.preferredSize = Dimension(mainClass.preferredSize.width, 200)
    workingDir.preferredSize = Dimension(mainClass.preferredSize.width, workingDir.preferredSize.height)

    panel.layout = GridLayoutManager(10, 2, JBUI.emptyInsets(), -1, -1)
    var currentRow = 0;
    panel.add(
      enabled, GridConstraints(
        currentRow, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
        GridConstraints.SIZEPOLICY_CAN_SHRINK or GridConstraints.SIZEPOLICY_CAN_GROW,
        GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false
      )
    )
    panel.add(JLabel("Class Path"), left(++currentRow)); panel.add(classPath, right(currentRow))
    panel.add(JLabel("Main Class"), left(++currentRow)); panel.add(mainClass, right(currentRow))
    panel.add(
      useStandardIn, GridConstraints(
        ++currentRow, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
        GridConstraints.SIZEPOLICY_CAN_SHRINK or GridConstraints.SIZEPOLICY_CAN_GROW,
        GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false
      )
    )
    panel.add(JLabel("Arguments"), left(++currentRow)); panel.add(arguments, right(currentRow))
    panel.add(JLabel("Working Dir"), left(++currentRow)); panel.add(workingDir, right(currentRow))
    panel.add(JLabel("VM-Options"), left(++currentRow)); panel.add(vmOptionsPane, right(currentRow, Dimension(50, 50)))
    panel.add(JLabel("Test-Code"), left(++currentRow)); panel.add(testCodePane, right(currentRow, Dimension(50, 50)))
    panel.add(testButton, left(++currentRow)); panel.add(JLabel(""), right(currentRow))
    panel.add(
      Spacer(), GridConstraints(
        ++currentRow, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL,
        1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false
      )
    )
    return panel
  }

  private fun formatTestCode() {
    if (!enabled.isSelected) {
      JOptionPane.showMessageDialog(
        testButton, "Formatter must be enabled via the checkbox.",
        "Formatting could not be started", JOptionPane.ERROR_MESSAGE
      )
      return
    }
    val projectSdk: Sdk? = ProjectRootManager.getInstance(project).projectSdk
    if (projectSdk == null) {
      JOptionPane.showMessageDialog(
        testButton, "A project SDK must be selected under Project settings.",
        "Formatting could not be started", JOptionPane.ERROR_MESSAGE
      )
      return
    }
    val tempFile = File.createTempFile("external-java-format-test", ".java")
    tempFile.deleteOnExit()
    tempFile.writeText(testCode.text)
    val commandLine = FormattingRequestExecutor.createCommandLine(
      tempFile, projectSdk, workingDir.text, mainClass.text, classPath.text, arguments.text, vmOptions.text
    )


    val (exitCode, stdOut, stdErr) = execute(commandLine, testCode.text)

    if (exitCode == 0) {
      val firstDiffPos = findFirstDiffPos(testCode.text, stdOut)
      testCode.text = stdOut
      testCode.caretPosition = max(firstDiffPos, 0)
      testCode.requestFocus()
      JOptionPane.showMessageDialog(
        testButton,
        stdErr.ifBlank { "Ok - Using SDK ${projectSdk.name}/${projectSdk.versionString}" },
        "Formatting was successfully completed", JOptionPane.INFORMATION_MESSAGE
      )
    } else JOptionPane.showMessageDialog(
      testButton,
      stdErr.ifBlank { "Error $exitCode - Using SDK ${projectSdk.name}/${projectSdk.versionString}" },
      "Formatting was terminated with an error $exitCode", JOptionPane.ERROR_MESSAGE
    )
  }

  private fun execute(commandLine: GeneralCommandLine, stdIn: String? = null): Triple<Int, String, String> {
    var stdErr = "";
    var stdOut = "";
    var exitCode = -1;

    ProgressManager.getInstance().runProcessWithProgressSynchronously({
      CapturingProcessHandler(commandLine).also { processHandler ->
        processHandler.addProcessListener(object : ProcessAdapter() {
          override fun startNotified(event: ProcessEvent) {
            if (useStandardIn.isSelected && stdIn != null) {
              processHandler.processInput.writer(commandLine.charset).use { it.write(stdIn) }
            }
          }
        })
      }.runProcess(timeoutInSeconds * 1000).also { output ->
        stdErr = output.stderr
        stdOut = output.stdout
        exitCode = output.exitCode
      }
    }, "Test formatting ...", true, project)
    return Triple(exitCode, stdOut, stdErr)
  }

  private fun findFirstDiffPos(a: String, b: String): Int {
    var i = 0
    if (a == b) return -1
    while (i < a.length && i < b.length && a[i] == b[i]) i++
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