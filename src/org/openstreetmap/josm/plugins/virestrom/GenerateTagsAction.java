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
import org.openstreetmap.josm.tools.Geometry;

public class GenerateTagsAction extends JosmAction {

    private static final Random RANDOM = new Random();

    // Session memory
    private static String lastCity = "";
    private static String lastPostcode = "";
    private static String lastStreet = "";
    private static String lastHouseNumber = "";
    private static String lastIncrement = "1";
    private static String lastTargetRes = "2.5";
    private static String lastLevels = "3";
    private static String lastUnitSize = "75";
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
        String[] types = {"House", "Industrial", "Commercial", "Apartments"};
        JComboBox<String> typeCombo = new JComboBox<>(types);
        typeCombo.setSelectedIndex(lastTypeIndex);

        JTextField cityField = new JTextField(lastCity, 20);
        JTextField postField = new JTextField(lastPostcode, 20);
        JTextField streetField = new JTextField(detectedStreet, 20);
        JTextField houseNumField = new JTextField(lastHouseNumber, 20);
        JTextField incrementField = new JTextField(lastIncrement, 20);
        JTextField targetResField = new JTextField(lastTargetRes, 20);
        JTextField levelsField = new JTextField(lastLevels, 20);
        JTextField unitSizeField = new JTextField(lastUnitSize, 20);

        JTextField countryField = new JTextField(Config.getPref().get("virestrom.country", "FSA"), 20);
        JTextField stateField = new JTextField(Config.getPref().get("virestrom.state", "MS"), 20);

        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("Building Type:")); panel.add(typeCombo);
        panel.add(new JLabel("Target Residents")); panel.add(targetResField);
        panel.add(new JLabel("Levels (Apts only):")); panel.add(levelsField);
        panel.add(new JLabel("Unit Size m² (Apts only):")); panel.add(unitSizeField);
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
        lastLevels = levelsField.getText();
        lastUnitSize = unitSizeField.getText();

        double targetResidentsValue = parseDouble(lastTargetRes, 2.5);
        int levelsValue = (int) parseDouble(lastLevels, 1.0);
        double unitSizeValue = parseDouble(lastUnitSize, 75.0);

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
                } else if ("Apartments".equals(selectedType)) {
                    applyApartmentLogic(osm, levelsValue, unitSizeValue, targetResidentsValue);
                } else {
                    applyBusinessLogic(osm, selectedType.toLowerCase());
                }
            }
        } finally {
            ds.endUpdate();
        }
    }

    private void applyApartmentLogic(OsmPrimitive osm, int levels, double unitSize, double targetResPerUnit) {
        double area = 0;
        if (osm instanceof Way) {
            area = Geometry.computeArea((Way) osm);
        }

        // Calculate units based on total floor area
        double totalFloorArea = area * levels;
        int units = (int) Math.max(1, Math.round(totalFloorArea / unitSize));

        // Sum residents across all units using your existing weighted logic
        int totalResidents = 0;
        for (int i = 0; i < units; i++) {
            totalResidents += getWeightedResidents(targetResPerUnit);
        }

        // Height: ~3.5m per level + random variation
        double height = (levels * 3.5) + (RANDOM.nextDouble() * 2.0);

        osm.put("building", "apartments");
        osm.put("building:levels", String.valueOf(levels));
        osm.put("building:units", String.valueOf(units));
        osm.put("building:residents", String.valueOf(totalResidents));
        osm.put("height", String.format("%.1f", height));

        // Clean up business tags if they existed
        osm.remove("employees"); osm.remove("ms:ccode");
    }

    private void applyHouseLogic(OsmPrimitive osm, double target) {
        int levels = (RANDOM.nextDouble() < 0.7) ? 1 : 2;
        double height = (levels == 1) ? 3.0 + (RANDOM.nextDouble() * 1.5) : 5.5 + (RANDOM.nextDouble() * 2.0);
        osm.put("building", "house");
        osm.put("building:levels", String.valueOf(levels));
        osm.put("height", String.format("%.1f", height));

        int res = getWeightedResidents(target);
        osm.put("building:residents", String.valueOf(res));
        osm.remove("employees"); osm.remove("ms:ccode"); osm.remove("name");
        osm.remove("building:units");
    }

    private int getWeightedResidents(double target) {
        double L = Math.exp(-target);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= RANDOM.nextDouble();
        } while (p > L);

        int result = k - 1;
        if (result < 1) result = 1;
        if (result >= 10) {
            result = 10 + RANDOM.nextInt(11);
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
        osm.remove("building:units");
    }

    private double parseDouble(String val, double fallback) {
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
