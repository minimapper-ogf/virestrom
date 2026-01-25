package org.openstreetmap.josm.plugins.virestrom;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.stream.Collectors;
import javax.swing.*;
import java.awt.GridLayout;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.spi.preferences.Config;

public class GenerateAdminTagsAction extends JosmAction {

    private static String lastAdminLevel = "8";
    private static String lastCounty = "";

    public GenerateAdminTagsAction() {
        super(
            "Create Admin Bound Tags",
            "boundary",
            "Create administrative boundary relations (innter ways must be done manually)",
            Shortcut.registerShortcut("virestrom:admin", "Create Admin Tags", KeyEvent.VK_V, Shortcut.CTRL_SHIFT),
            false
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        var lm = MainApplication.getLayerManager();
        if (lm.getEditDataSet() == null) return;
        var ds = lm.getEditDataSet();

        // Filter selection to only include Ways
        List<Way> selectedWays = ds.getSelected().stream()
            .filter(p -> p instanceof Way)
            .map(p -> (Way) p)
            .collect(Collectors.toList());

        if (selectedWays.isEmpty()) return;

        // UI Setup
        JTextField nameField = new JTextField("", 20);
        JTextField popField = new JTextField("", 20);
        JTextField adminLevelField = new JTextField(lastAdminLevel, 20);
        JTextField countyField = new JTextField(lastCounty, 20);
        JTextField stateField = new JTextField(Config.getPref().get("virestrom.state", "MS"), 20);

        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel("Name:")); panel.add(nameField);
        panel.add(new JLabel("Population:")); panel.add(popField);
        panel.add(new JLabel("Admin Level:")); panel.add(adminLevelField);
        panel.add(new JLabel("is_in:county:")); panel.add(countyField);
        panel.add(new JLabel("is_in:state (Perm):")); panel.add(stateField);

        ExtendedDialog diag = new ExtendedDialog(MainApplication.getMainFrame(), "Virestrom Admin Level Tag Creator", new String[] {"Create Relation", "Cancel"});
        diag.setContent(panel);
        diag.showDialog();

        if (diag.getValue() != 1) return;

        lastAdminLevel = adminLevelField.getText();
        lastCounty = countyField.getText();
        Config.getPref().put("virestrom.state", stateField.getText());

        ds.beginUpdate();
        try {
            Relation newRelation = new Relation();
            newRelation.put("type", "boundary");
            newRelation.put("boundary", "administrative");
            newRelation.put("name", nameField.getText());
            newRelation.put("admin_level", lastAdminLevel);
            newRelation.put("is_in:state", stateField.getText());
            if (!popField.getText().isEmpty()) newRelation.put("population", popField.getText());
            if (!lastCounty.isEmpty()) newRelation.put("is_in:county", lastCounty);

            // AUTO-ROLE DETECTION LOGIC
            // We find the 'largest' way or group of ways.
            // Simplified for OGF: any way that has its nodes completely contained
            // within the area of another selected way is 'inner'.

            for (Way w : selectedWays) {
                String role = "outer"; // Default

                for (Way other : selectedWays) {
                    if (w == other) continue;

                    // Check if 'w' is inside 'other'
                    // Using Bounding Box as a fast check, then checking a sample node
                    if (other.isClosed() && other.getBBox().contains(w.getBBox())) {
                        // If 'other' contains the first node of 'w', it's an enclave
                        if (other.containsNode(w.getNode(0))) {
                            role = "inner";
                            break;
                        }
                    }
                }
                newRelation.addMember(new RelationMember(role, w));
            }

            ds.addPrimitive(newRelation);
        } finally {
            ds.endUpdate();
        }
    }
}
