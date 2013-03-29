/**
 * DataCleaner (community edition)
 * Copyright (C) 2013 Human Inference
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.eobjects.datacleaner.panels;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.border.EmptyBorder;

import org.eobjects.analyzer.configuration.AnalyzerBeansConfiguration;
import org.eobjects.datacleaner.extensions.ExtensionReader;
import org.eobjects.datacleaner.user.ExtensionPackage;
import org.eobjects.datacleaner.user.UserPreferences;
import org.eobjects.datacleaner.util.ExtensionFilter;
import org.eobjects.datacleaner.util.IconUtils;
import org.eobjects.datacleaner.util.ImageManager;
import org.eobjects.datacleaner.util.WidgetFactory;
import org.eobjects.datacleaner.util.WidgetUtils;
import org.eobjects.datacleaner.widgets.DCFileChooser;
import org.eobjects.datacleaner.widgets.DCLabel;
import org.jdesktop.swingx.VerticalLayout;
import org.jdesktop.swingx.action.OpenBrowserAction;

import cern.colt.Arrays;

/**
 * Panel for configuring extension packages.
 * 
 * @author Kasper Sørensen
 */
public class ExtensionPackagesPanel extends DCPanel {

    private static final long serialVersionUID = 1L;

    private static final ImageManager imageManager = ImageManager.getInstance();

    private static final ImageIcon ICON_PLUGIN = imageManager.getImageIcon("images/component-types/plugin.png");
    private static final ImageIcon ICON_ERROR = imageManager.getImageIcon(IconUtils.STATUS_ERROR);

    private final UserPreferences _userPreferences;
    private final AnalyzerBeansConfiguration _configuration;

    @Inject
    protected ExtensionPackagesPanel(AnalyzerBeansConfiguration configuration, UserPreferences userPreferences) {
        super(WidgetUtils.BG_COLOR_BRIGHT, WidgetUtils.BG_COLOR_BRIGHTEST);
        _configuration = configuration;
        _userPreferences = userPreferences;

        setLayout(new BorderLayout());

        updateComponents();
    }

    private void updateComponents() {
        removeAll();

        final List<ExtensionPackage> extensionPackages = new ArrayList<ExtensionPackage>();
        extensionPackages.addAll(_userPreferences.getExtensionPackages());
        extensionPackages.addAll(new ExtensionReader().getInternalExtensions());

        final JButton addExtensionButton = new JButton("Add extension package",
                imageManager.getImageIcon(IconUtils.ACTION_ADD));
        addExtensionButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final JMenuItem extensionSwapMenuItem = new JMenuItem("Browse the ExtensionSwap", imageManager
                        .getImageIcon("images/actions/website.png"));
                extensionSwapMenuItem.addActionListener(new OpenBrowserAction("http://datacleaner.org/extensions"));

                final JMenuItem manualInstallMenuItem = new JMenuItem("Manually install JAR file", imageManager
                        .getImageIcon("images/filetypes/archive.png"));
                manualInstallMenuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        final DCFileChooser fileChooser = new DCFileChooser(_userPreferences
                                .getConfiguredFileDirectory());
                        fileChooser.setMultiSelectionEnabled(true);
                        fileChooser.setFileFilter(new ExtensionFilter("DataCleaner extension JAR file (.jar)", ".jar"));
                        int result = fileChooser.showOpenDialog(ExtensionPackagesPanel.this);
                        if (result == DCFileChooser.APPROVE_OPTION) {

                            final File[] files = fileChooser.getSelectedFiles();

                            final ExtensionReader extensionReader = new ExtensionReader();
                            final ExtensionPackage extensionPackage = extensionReader.readExternalExtension(files);

                            extensionPackage.loadDescriptors(_configuration.getDescriptorProvider());
                            _userPreferences.addExtensionPackage(extensionPackage);

                            updateComponents();
                        }
                    }
                });

                final JPopupMenu popup = new JPopupMenu("Add extension");
                popup.add(extensionSwapMenuItem);
                popup.add(manualInstallMenuItem);
                popup.show(addExtensionButton, 0, addExtensionButton.getHeight());
            }
        });

        final JToolBar toolBar = WidgetFactory.createToolBar();
        toolBar.add(WidgetFactory.createToolBarSeparator());
        toolBar.add(addExtensionButton);

        final DCPanel listPanel = new DCPanel();
        listPanel.setLayout(new VerticalLayout(4));
        listPanel.setBorder(new EmptyBorder(0, 10, 10, 0));

        for (final ExtensionPackage extensionPackage : extensionPackages) {
            final DCPanel extensionPanel = createExtensionPanel(extensionPackage);
            listPanel.add(extensionPanel);
        }

        if (extensionPackages.isEmpty()) {
            listPanel.add(DCLabel.dark("(none)"));
        }

        add(toolBar, BorderLayout.NORTH);
        add(listPanel, BorderLayout.CENTER);

        updateUI();
    }

    private DCPanel createExtensionPanel(final ExtensionPackage extensionPackage) {
        boolean valid = true;
        final File[] files = extensionPackage.getFiles();
        if (extensionPackage.isExternal()) {
            for (File file : files) {
                if (!file.exists()) {
                    valid = false;
                }
            }
        }

        final DCLabel extensionLabel;
        if (valid) {
            final String iconPath = extensionPackage.getAdditionalProperties().get("icon");
            final ImageIcon extensionIcon;
            if (iconPath != null) {
                final ImageIcon imageIcon = imageManager.getImageIcon(iconPath, IconUtils.ICON_SIZE_LARGE,
                        ExtensionPackage.getExtensionClassLoader());
                if (imageIcon == null) {
                    extensionIcon = ICON_PLUGIN;
                } else {
                    extensionIcon = imageIcon;
                }
            } else {
                extensionIcon = ICON_PLUGIN;
            }

            final StringBuilder labelBuilder = new StringBuilder();
            labelBuilder.append("<html><b>");
            labelBuilder.append(extensionPackage.getName());
            labelBuilder.append("</b>");

            final String description = extensionPackage.getDescription();
            if (description != null) {
                labelBuilder.append("<br/>");
                labelBuilder.append(description);
            }

            final String version = extensionPackage.getVersion();
            if (version != null) {
                labelBuilder.append("<br/>Version ");
                labelBuilder.append(version);
            }

            labelBuilder.append("</html>");

            extensionLabel = DCLabel.dark(labelBuilder.toString());
            extensionLabel.setIcon(extensionIcon);
        } else {
            extensionLabel = DCLabel.dark("<html><b>" + extensionPackage.getName()
                    + "</b><br/>Error loading extension files:<br/>" + Arrays.toString(files) + "</html>");
            extensionLabel.setIcon(ICON_ERROR);
        }

        final DCPanel extensionPanel = new DCPanel();
        extensionPanel.setBorder(WidgetUtils.BORDER_LIST_ITEM);
        WidgetUtils.addToGridBag(extensionLabel, extensionPanel, 0, 0, 1.0, 0.0);

        if (extensionPackage.isExternal()) {
            final JButton removeButton = WidgetFactory.createSmallButton(IconUtils.ACTION_REMOVE);
            removeButton.setToolTipText("Remove extension");
            removeButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    _userPreferences.removeExtensionPackage(extensionPackage);
                    removeButton.setEnabled(false);
                    extensionLabel.setText("*** Removal requires application restart ***");
                }
            });
            WidgetUtils.addToGridBag(removeButton, extensionPanel, 1, 0, GridBagConstraints.EAST);
        }

        return extensionPanel;
    }
}
