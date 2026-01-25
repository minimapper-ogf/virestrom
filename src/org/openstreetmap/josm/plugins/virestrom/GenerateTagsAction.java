package org.openstreetmap.josm.plugins.virestrom;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.Random;
import javax.swing.*;
import java.awt.GridLayout;
import java.awt.Color;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.spi.preferences.Config;

public class GenerateTagsAction extends JosmAction {

    private static final Random RANDOM = new Random();

    // Session memory (resets on JOSM restart)
    private static String lastCity = "";
    private static String lastPostcode = "";
    private static String lastStreet = "";
    private static int lastTypeIndex = 0;

    public GenerateTagsAction() {
        super(
            "Generate Building Tags (Virestrom)",
            "building",
            "Generate weighted building tags for different types",
            Shortcut.registerShortcut(
                "virestrom:generate",
                "Generate Building Tags (Virestrom)",
                KeyEvent.VK_X,
                Shortcut.CTRL_SHIFT
            ),
            false
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        var lm = MainApplication.getLayerManager();
        if (lm.getEditDataSet() == null) return;

        var ds = lm.getEditDataSet();
        Collection<OsmPrimitive> selection = ds.getSelected();
        if (selection.isEmpty()) return;

        // UI Components
        String[] types = {"House", "Industrial", "Commercial"};
        JComboBox<String> typeCombo = new JComboBox<>(types);
        typeCombo.setSelectedIndex(lastTypeIndex);

        JTextField cityField = new JTextField(lastCity);
        JTextField postField = new JTextField(lastPostcode);
        JTextField streetField = new JTextField(lastStreet);
        JTextField houseNumField = new JTextField();

        // Permanent Settings (Loaded from JOSM config)
        JTextField countryField = new JTextField(Config.getPref().get("virestrom.country", "FSA"));
        JTextField stateField = new JTextField(Config.getPref().get("virestrom.state", "MS"));

        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));

        panel.add(new JLabel("Building Type:")); panel.add(typeCombo);
        panel.add(new JLabel("City:")); panel.add(cityField);
        panel.add(new JLabel("Postcode:")); panel.add(postField);
        panel.add(new JLabel("Street:")); panel.add(streetField);
        panel.add(new JLabel("House Number:")); panel.add(houseNumField);

        // Visual separator
        panel.add(new JSeparator()); panel.add(new JSeparator());
        JLabel label = new JLabel("Regional (Permanent):");
        label.setForeground(Color.GRAY);
        panel.add(label); panel.add(new JLabel(""));

        panel.add(new JLabel("Country:")); panel.add(countryField);
        panel.add(new JLabel("State:")); panel.add(stateField);

        ExtendedDialog diag = new ExtendedDialog(
            MainApplication.getMainFrame(),
            "Virestrom Suite",
            new String[] {"Apply", "Cancel"}
        );
        diag.setContent(panel);
        diag.showDialog();

        if (diag.getValue() != 1) return;

        // Save session data
        lastTypeIndex = typeCombo.getSelectedIndex();
        lastCity = cityField.getText();
        lastPostcode = postField.getText();
        lastStreet = streetField.getText();

        // Save permanent data to JOSM Config
        Config.getPref().put("virestrom.country", countryField.getText());
        Config.getPref().put("virestrom.state", stateField.getText());

        ds.beginUpdate();
        try {
            for (OsmPrimitive osm : selection) {
                // Apply Address Tags
                if (!lastCity.isEmpty()) osm.put("addr:city", lastCity);
                if (!lastPostcode.isEmpty()) osm.put("addr:postcode", lastPostcode);
                if (!lastStreet.isEmpty()) osm.put("addr:street", lastStreet);
                if (!houseNumField.getText().isEmpty()) osm.put("addr:housenumber", houseNumField.getText());

                // Use the values directly from the input fields
                osm.put("addr:country", countryField.getText());
                osm.put("addr:state", stateField.getText());

                String selectedType = (String) typeCombo.getSelectedItem();
                if ("House".equals(selectedType)) {
                    applyHouseLogic(osm);
                } else {
                    applyBusinessLogic(osm, selectedType.toLowerCase());
                }
            }
        } finally {
            ds.endUpdate();
        }
    }

    private void applyHouseLogic(OsmPrimitive osm) {
        int levels = (RANDOM.nextDouble() < 0.7) ? 1 : 2;
        double height = (levels == 1) ? 3.0 + (RANDOM.nextDouble() * 1.5) : 5.5 + (RANDOM.nextDouble() * 2.0);
        osm.put("building", "house");
        osm.put("building:levels", String.valueOf(levels));
        osm.put("height", String.format("%.1f", height));
        double r = RANDOM.nextDouble();
        int res = (r < 0.2) ? 1 : (r < 0.6) ? 2 : (r < 0.85) ? 3 : (r < 0.95) ? 4 : 5;
        osm.put("building:residents", String.valueOf(res));
        osm.remove("employees"); osm.remove("ms:ccode"); osm.remove("name");
    }

    private void applyBusinessLogic(OsmPrimitive osm, String type) {
        osm.put("building", type);
        osm.put("building:levels", "1");
        double height = 6.0 + (RANDOM.nextDouble() * 9.0);
        osm.put("height", String.format("%.1f", height));
        osm.put("employees", "xxx");
        osm.put("ms:ccode", "xx-xxxxx");
        osm.put("name", "xxx");
        osm.remove("building:residents");
    }
}
