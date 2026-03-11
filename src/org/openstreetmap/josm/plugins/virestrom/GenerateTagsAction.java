package org.openstreetmap.josm.plugins.virestrom;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import javax.swing.*;
import java.awt.GridLayout;
import java.awt.Color;
import java.awt.FlowLayout;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.OpenBrowser;

public class GenerateTagsAction extends JosmAction {

    private static final Random RANDOM = new Random();
    private static final String DOC_URL = "https://minimapper.net";

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
            "Generate weighted tags with logic (never, above, below)",
            Shortcut.registerShortcut("virestrom:generate", "Create Building Tags", KeyEvent.VK_X, Shortcut.CTRL_SHIFT),
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

        // UI setup remains the same
        String[] types = {"House", "Industrial", "Commercial", "Apartments"};
        JComboBox<String> typeCombo = new JComboBox<>(types);
        typeCombo.setSelectedIndex(lastTypeIndex);

        JTextField levelsField = new JTextField(lastLevels, 20);
        JTextField targetResField = new JTextField(lastTargetRes, 20);
        JTextField unitSizeField = new JTextField(lastUnitSize, 20);
        JTextField cityField = new JTextField(lastCity, 20);
        JTextField postField = new JTextField(lastPostcode, 20);
        JTextField streetField = new JTextField(detectedStreet, 20);
        JTextField houseNumField = new JTextField(lastHouseNumber, 20);
        JTextField incrementField = new JTextField(lastIncrement, 20);
        JTextField countryField = new JTextField(Config.getPref().get("virestrom.country", "FSA"), 20);
        JTextField stateField = new JTextField(Config.getPref().get("virestrom.state", "MS"), 20);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        JPanel gridPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        gridPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        gridPanel.add(new JLabel("Building Type:")); gridPanel.add(typeCombo);
        gridPanel.add(new JLabel("Levels:")); gridPanel.add(levelsField);
        gridPanel.add(new JLabel("Residents:")); gridPanel.add(targetResField);
        gridPanel.add(new JLabel("Unit Size m² (Apts):")); gridPanel.add(unitSizeField);
        gridPanel.add(new JLabel("City:")); gridPanel.add(cityField);
        gridPanel.add(new JLabel("Postcode:")); gridPanel.add(postField);
        gridPanel.add(new JLabel("Street:")); gridPanel.add(streetField);
        gridPanel.add(new JLabel("House Number:")); gridPanel.add(houseNumField);
        gridPanel.add(new JLabel("Increment by:")); gridPanel.add(incrementField);
        gridPanel.add(new JSeparator()); gridPanel.add(new JSeparator());
        gridPanel.add(new JLabel("Country:")); gridPanel.add(countryField);
        gridPanel.add(new JLabel("State:")); gridPanel.add(stateField);

        JPanel helpPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton helpButton = new JButton("Read Documentation / Help");
        helpButton.setFocusable(false);
        helpButton.addActionListener(al -> OpenBrowser.displayUrl(DOC_URL));
        helpPanel.add(helpButton);

        mainPanel.add(gridPanel);
        mainPanel.add(helpPanel);

        ExtendedDialog diag = new ExtendedDialog(MainApplication.getMainFrame(), "Virestrom Building Tag Creator", new String[] {"Apply", "Cancel"});
        diag.setContent(mainPanel);
        diag.showDialog();

        if (diag.getValue() != 1) return;

        lastTypeIndex = typeCombo.getSelectedIndex();
        lastCity = cityField.getText();
        lastPostcode = postField.getText();
        lastStreet = streetField.getText();
        lastIncrement = incrementField.getText();
        lastTargetRes = targetResField.getText();
        lastUnitSize = unitSizeField.getText();
        lastLevels = levelsField.getText();

        double unitSizeValue = parseDouble(lastUnitSize, 75.0);
        String currentHouseNum = houseNumField.getText();
        String countryVal = countryField.getText().trim();
        String stateVal = stateField.getText().trim();

        try {
            int num = Integer.parseInt(currentHouseNum);
            int inc = Integer.parseInt(lastIncrement);
            lastHouseNumber = String.valueOf(num + inc);
        } catch (NumberFormatException ex) { lastHouseNumber = currentHouseNum; }

        Config.getPref().put("virestrom.country", countryVal);
        Config.getPref().put("virestrom.state", stateVal);

        ds.beginUpdate();
        try {
            for (OsmPrimitive osm : selection) {
                if (osm instanceof Way && osm.hasTag("highway")) continue;
                if (!lastCity.isEmpty()) osm.put("addr:city", lastCity);
                if (!lastPostcode.isEmpty()) osm.put("addr:postcode", lastPostcode);
                if (!lastStreet.isEmpty()) osm.put("addr:street", lastStreet);
                if (!currentHouseNum.isEmpty()) osm.put("addr:housenumber", currentHouseNum);
                if (!countryVal.isEmpty()) osm.put("addr:country", countryVal); else osm.remove("addr:country");
                if (!stateVal.isEmpty()) osm.put("addr:state", stateVal); else osm.remove("addr:state");

                String selectedType = (String) typeCombo.getSelectedItem();
                int finalLevels = getWeightedValue(lastLevels, "House".equals(selectedType));

                if ("House".equals(selectedType)) {
                    applyHouseLogic(osm, finalLevels, lastTargetRes);
                } else if ("Apartments".equals(selectedType)) {
                    applyApartmentLogic(osm, finalLevels, unitSizeValue, lastTargetRes);
                } else {
                    applyBusinessLogic(osm, selectedType.toLowerCase(), finalLevels);
                }
            }
        } finally {
            ds.endUpdate();
        }
    }

    private int getWeightedValue(String input, boolean shouldRandomize) {
        String raw = input.toLowerCase();
        // Extract the target (first number in the string)
        double target = parseDouble(raw.split("\\s+")[0], 1.0);

        if (!shouldRandomize) return (int) Math.round(target);

        Set<Integer> forbidden = new HashSet<>();
        double aboveLimit = Double.MAX_VALUE;
        double belowLimit = -Double.MAX_VALUE;

        // Parse Logic
        if (raw.contains("never")) {
            String part = raw.split("never")[1].split("above|below")[0];
            for (String s : part.split(",")) forbidden.add((int) parseDouble(s.trim(), -1.0));
        }
        if (raw.contains("above")) {
            aboveLimit = parseDouble(raw.split("above")[1].split("never|below")[0], Double.MAX_VALUE);
        }
        if (raw.contains("below")) {
            belowLimit = parseDouble(raw.split("below")[1].split("never|above")[0], -Double.MAX_VALUE);
        }

        int picked;
        int attempts = 0;
        do {
            picked = calculatePoisson(target);
            attempts++;
            // Break loop if we are stuck to avoid freezing JOSM
            if (attempts > 100) break;
        } while (forbidden.contains(picked) || picked > aboveLimit || picked < belowLimit);

        return picked;
    }

    private int calculatePoisson(double target) {
        double L = Math.exp(-target);
        int k = 0;
        double p = 1.0;
        do { k++; p *= RANDOM.nextDouble(); } while (p > L);
        int result = k - 1;
        if (result < 1) result = 1;
        // Poisson naturally has a "long tail", this keeps things somewhat sane if target is high
        if (result >= 15) result = 15 + RANDOM.nextInt(6);
        return result;
    }

    private void applyApartmentLogic(OsmPrimitive osm, int levels, double unitSize, String resInput) {
        double area = (osm instanceof Way) ? Geometry.computeArea((Way) osm) : 0;
        int units = (int) Math.max(1, Math.round((area * levels) / unitSize));
        int totalResidents = 0;
        for (int i = 0; i < units; i++) totalResidents += getWeightedValue(resInput, true);

        osm.put("building", "apartments");
        osm.put("building:levels", String.valueOf(levels));
        osm.put("building:units", String.valueOf(units));
        osm.put("building:residents", String.valueOf(totalResidents));
        applyHeight(osm, levels, 3.2);
        osm.remove("employees"); osm.remove("ms:ccode");
    }

    private void applyHouseLogic(OsmPrimitive osm, int levels, String resInput) {
        osm.put("building", "house");
        osm.put("building:levels", String.valueOf(levels));
        osm.put("building:residents", String.valueOf(getWeightedValue(resInput, true)));
        applyHeight(osm, levels, 3.0);
        osm.remove("employees"); osm.remove("ms:ccode"); osm.remove("name"); osm.remove("building:units");
    }

    private void applyBusinessLogic(OsmPrimitive osm, String type, int levels) {
        osm.put("building", type);
        osm.put("building:levels", String.valueOf(levels));
        applyHeight(osm, levels, 4.0);
        osm.put("employees", "xxx");
        osm.put("ms:ccode", "xx-xxxxx");
        osm.put("name", "xxx");
        osm.remove("building:residents"); osm.remove("building:units");
    }

    private void applyHeight(OsmPrimitive osm, int levels, double avgFloorHeight) {
        double floorHeight = avgFloorHeight + (RANDOM.nextDouble() * 0.7 - 0.2);
        osm.put("height", String.format("%.1f", levels * floorHeight));
    }

    private double parseDouble(String val, double fallback) {
        try { return Double.parseDouble(val.replaceAll("[^0-9.]", "")); }
        catch (Exception e) { return fallback; }
    }
}
