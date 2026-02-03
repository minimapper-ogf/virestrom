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
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.spi.preferences.Config;

public class GenerateTagsAction extends JosmAction {

    private static final Random RANDOM = new Random();

    // Session memory
    private static String lastCity = "";
    private static String lastPostcode = "";
    private static String lastStreet = "";
    private static String lastHouseNumber = "";
    private static String lastIncrement = "1";
    private static String lastTargetRes = "2.5"; // Default target average
    private static int lastTypeIndex = 0;

    public GenerateTagsAction() {
        super(
            "Create Building Tags",
            "building",
            "Generate weighted building tags for different types",
            Shortcut.registerShortcut(
                "virestrom:generate",
                "Create Building Tags",
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

        String detectedStreet = lastStreet;
        for (OsmPrimitive osm : selection) {
            if (osm instanceof Way && osm.hasTag("name")) {
                detectedStreet = osm.get("name");
                break;
            }
        }

        // UI Components
        String[] types = {"House", "Industrial", "Commercial"};
        JComboBox<String> typeCombo = new JComboBox<>(types);
        typeCombo.setSelectedIndex(lastTypeIndex);

        JTextField cityField = new JTextField(lastCity, 20);
        JTextField postField = new JTextField(lastPostcode, 20);
        JTextField streetField = new JTextField(detectedStreet, 20);
        JTextField houseNumField = new JTextField(lastHouseNumber, 20);
        JTextField incrementField = new JTextField(lastIncrement, 20);
        JTextField targetResField = new JTextField(lastTargetRes, 20); // NEW FIELD

        JTextField countryField = new JTextField(Config.getPref().get("virestrom.country", "FSA"), 20);
        JTextField stateField = new JTextField(Config.getPref().get("virestrom.state", "MS"), 20);

        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("Building Type:")); panel.add(typeCombo);
        panel.add(new JLabel("Target Residents:")); panel.add(targetResField); // Added to UI
        panel.add(new JLabel("City:")); panel.add(cityField);
        panel.add(new JLabel("Postcode:")); panel.add(postField);
        panel.add(new JLabel("Street:")); panel.add(streetField);
        panel.add(new JLabel("House Number:")); panel.add(houseNumField);
        panel.add(new JLabel("Increment by:")); panel.add(incrementField);

        panel.add(new JSeparator()); panel.add(new JSeparator());
        JLabel label = new JLabel("Regional (Permanent):");
        label.setForeground(Color.GRAY);
        panel.add(label); panel.add(new JLabel(""));

        panel.add(new JLabel("Country:")); panel.add(countryField);
        panel.add(new JLabel("State:")); panel.add(stateField);

        ExtendedDialog diag = new ExtendedDialog(
            MainApplication.getMainFrame(),
            "Virestrom Building Tag Creator",
            new String[] {"Apply", "Cancel"}
        );
        diag.setContent(panel);
        diag.showDialog();

        if (diag.getValue() != 1) return;

        // Save session memory
        lastTypeIndex = typeCombo.getSelectedIndex();
        lastCity = cityField.getText();
        lastPostcode = postField.getText();
        lastStreet = streetField.getText();
        lastIncrement = incrementField.getText();
        lastTargetRes = targetResField.getText();

        double targetResidentsValue;
        try {
            targetResidentsValue = Double.parseDouble(lastTargetRes);
        } catch (NumberFormatException ex) {
            targetResidentsValue = 2.0; // Fallback
        }

        String currentHouseNum = houseNumField.getText();
        try {
            int num = Integer.parseInt(currentHouseNum);
            int inc = Integer.parseInt(lastIncrement);
            lastHouseNumber = String.valueOf(num + inc);
        } catch (NumberFormatException ex) {
            lastHouseNumber = currentHouseNum;
        }

        Config.getPref().put("virestrom.country", countryField.getText());
        Config.getPref().put("virestrom.state", stateField.getText());

        ds.beginUpdate();
        try {
            for (OsmPrimitive osm : selection) {
                if (osm instanceof Way && osm.hasTag("highway")) continue;

                if (!lastCity.isEmpty()) osm.put("addr:city", lastCity);
                if (!lastPostcode.isEmpty()) osm.put("addr:postcode", lastPostcode);
                if (!lastStreet.isEmpty()) osm.put("addr:street", lastStreet);
                if (!currentHouseNum.isEmpty()) osm.put("addr:housenumber", currentHouseNum);

                osm.put("addr:country", countryField.getText());
                osm.put("addr:state", stateField.getText());

                String selectedType = (String) typeCombo.getSelectedItem();
                if ("House".equals(selectedType)) {
                    applyHouseLogic(osm, targetResidentsValue);
                } else {
                    applyBusinessLogic(osm, selectedType.toLowerCase());
                }
            }
        } finally {
            ds.endUpdate();
        }
    }

    private void applyHouseLogic(OsmPrimitive osm, double target) {
        int levels = (RANDOM.nextDouble() < 0.7) ? 1 : 2;
        double height = (levels == 1) ? 3.0 + (RANDOM.nextDouble() * 1.5) : 5.5 + (RANDOM.nextDouble() * 2.0);
        osm.put("building", "house");
        osm.put("building:levels", String.valueOf(levels));
        osm.put("height", String.format("%.1f", height));

        // Use Poisson-like distribution for the weighted target
        int res = getWeightedResidents(target);

        osm.put("building:residents", String.valueOf(res));
        osm.remove("employees"); osm.remove("ms:ccode"); osm.remove("name");
    }

    /**
     * Generates a random number of residents based on a target average.
     */
    private int getWeightedResidents(double target) {
        // Knuth's algorithm for Poisson distribution
        double L = Math.exp(-target);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= RANDOM.nextDouble();
        } while (p > L);

        int result = k - 1;

        // Ensure we don't return 0 (every house needs at least 1 person)
        if (result < 1) result = 1;

        // Apply your 10+ logic: if it picks a high number,
        // randomize it between 10 and 20.
        if (result >= 10) {
            result = 10 + RANDOM.nextInt(11); // 10 to 20
        }

        return result;
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
