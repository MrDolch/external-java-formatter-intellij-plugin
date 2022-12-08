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

import com.intellij.openapi.options.BaseConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.uiDesigner.core.GridConstraints
import com.intellij.uiDesigner.core.GridLayoutManager
import com.intellij.uiDesigner.core.Spacer
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nls
import java.awt.Dimension
import javax.swing.*

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
    override fun getId(): String {
        return "external-java-formatter.settings"
    }

    override fun getDisplayName(): @Nls String? {
        return "external-java-formatter Settings"
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
        testCode.text = configuration.testCode
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
        panel.add(vmOptions, right(5, Dimension(50, 50)))
        panel.add(JLabel("Test-Code"), left(6))
        panel.add(testCode, right(6, Dimension(50, 50)))
        panel.add(JButton("Test"), left(7))
        panel.add(JLabel(""), left(7))
        panel.add(
            Spacer(), GridConstraints(
                8, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL,
                1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false
            )
        )
        return panel
    }

    private fun left(row: Int) = GridConstraints(
        row, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
        GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false
    )

    private fun right(row: Int, minimumSize: Dimension? = null) = GridConstraints(
        row, 1, 1, 1,
        GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW,
        GridConstraints.SIZEPOLICY_FIXED, minimumSize, null, null, 0, false
    )
}