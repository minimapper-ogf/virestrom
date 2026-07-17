package org.openstreetmap.josm.plugins.virestrom;

import javax.swing.SwingUtilities;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class VirestromPlugin extends Plugin {

    public VirestromPlugin(PluginInformation info) {
        super(info);
        GenerateTagsAction tagsAction = new GenerateTagsAction();
        GenerateAdminTagsAction adminAction = new GenerateAdminTagsAction();
        GenerateBuildingsAction buildingsAction = new GenerateBuildingsAction();
        SplitAction splitAction = new SplitAction();

        SwingUtilities.invokeLater(() -> {
            MainMenu menu = MainApplication.getMenu();

            menu.add(menu.toolsMenu, tagsAction);
            MainApplication.getToolbar().register(tagsAction);

            menu.add(menu.toolsMenu, adminAction);
            MainApplication.getToolbar().register(adminAction);

            menu.add(menu.toolsMenu, buildingsAction);
            MainApplication.getToolbar().register(buildingsAction);

            menu.add(menu.toolsMenu, splitAction);
            MainApplication.getToolbar().register(splitAction);
        });
    }
}
