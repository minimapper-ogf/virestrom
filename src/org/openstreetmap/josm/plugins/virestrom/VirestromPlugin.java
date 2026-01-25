package org.openstreetmap.josm.plugins.virestrom;

import javax.swing.SwingUtilities;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class VirestromPlugin extends Plugin {
    public VirestromPlugin(PluginInformation info) {
        super(info);
        GenerateTagsAction action = new GenerateTagsAction();
        SwingUtilities.invokeLater(() -> {
            MainMenu menu = MainApplication.getMenu();
            menu.add(menu.toolsMenu, action);
            MainApplication.getToolbar().register(action);
        });
    }
}
