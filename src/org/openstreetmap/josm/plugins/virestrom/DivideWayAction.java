package org.openstreetmap.josm.plugins.virestrom;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Shortcut;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

public class DivideWayAction extends JosmAction {

    // Persistent Settings
    private static String lastMode = "Distance"; // "Distance" or "Segments"
    private static String lastValue = "220";
    private static String lastUnit = "Feet";
    private static String lastErrorValue = "5";

    public DivideWayAction() {
        super("Divide Way with Nodes", "divide_way", "Inserts procedural nodes along ways at set distances or segments",
                Shortcut.registerShortcut("tools:divide_way_nodes", "Divide Way with Nodes", KeyEvent.VK_D, Shortcut.CTRL_SHIFT), true);
    }

    @Override
    public void actionPerformed(java.awt.event.ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) return;

        Collection<OsmPrimitive> selection = dataSet.getSelected();
        List<Way> targetWays = new ArrayList<>();

        for (OsmPrimitive prim : selection) {
            if (prim instanceof Way) {
                Way way = (Way) prim;
                if (!way.isClosed() && way.getNodesCount() >= 2) {
                    targetWays.add(way);
                }
            }
        }

        if (targetWays.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Please select at least one open way (road/path) to divide.",
                    "Divide Way", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // --- UI DIALOG CONFIGURATION ---
        JPanel mainPanel = new JPanel(new GridLayout(4, 2, 5, 5));

        JComboBox<String> modeBox = new JComboBox<>(new String[]{"Fixed Distance", "Equal Segments"});
        modeBox.setSelectedItem("Distance".equals(lastMode) ? "Fixed Distance" : "Equal Segments");

        JTextField valueField = new JTextField(lastValue);
        JComboBox<String> unitBox = new JComboBox<>(new String[]{"Feet", "Meters"});
        unitBox.setSelectedItem(lastUnit);

        JPanel valuePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        valuePanel.add(valueField);
        valuePanel.add(unitBox);

        JTextField errorField = new JTextField(lastErrorValue);

        modeBox.addActionListener(evt -> {
            boolean isDistanceMode = "Fixed Distance".equals(modeBox.getSelectedItem());
            unitBox.setEnabled(isDistanceMode);
        });
        unitBox.setEnabled("Distance".equals(lastMode));

        mainPanel.add(new JLabel("Division Mode:"));
        mainPanel.add(modeBox);
        mainPanel.add(new JLabel("Interval / Count:"));
        mainPanel.add(valuePanel);
        mainPanel.add(new JLabel("Max Error Jitter (+/-):"));
        mainPanel.add(errorField);

        int result = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), mainPanel,
                "Divide Way Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        // Parse Inputs
        boolean isDistanceMode = "Fixed Distance".equals(modeBox.getSelectedItem());
        lastMode = isDistanceMode ? "Distance" : "Segments";
        lastValue = valueField.getText();
        lastUnit = (String) unitBox.getSelectedItem();
        lastErrorValue = errorField.getText();

        double inputVal;
        double errorVal;

        try {
            inputVal = Double.parseDouble(lastValue);
            errorVal = Double.parseDouble(lastErrorValue);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Invalid numerical input.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (inputVal <= 0) return;

        double unitConversion = "Feet".equals(lastUnit) ? 0.3048 : 1.0;
        double maxErrorMeters = Math.max(0, errorVal) * unitConversion;

        List<Command> commands = new ArrayList<>();
        Random rand = new Random();

        for (Way way : targetWays) {
            List<Node> currentNodes = new ArrayList<>(way.getNodes());

            // Calculate cumulative distance along way
            double[] cumDist = new double[currentNodes.size()];
            cumDist[0] = 0.0;
            for (int i = 1; i < currentNodes.size(); i++) {
                cumDist[i] = cumDist[i - 1] + currentNodes.get(i - 1).getCoor().greatCircleDistance(currentNodes.get(i).getCoor());
            }
            double totalWayLength = cumDist[cumDist.length - 1];

            List<Double> targetDistances = new ArrayList<>();

            if (isDistanceMode) {
                double intervalMeters = inputVal * unitConversion;
                double dist = intervalMeters;
                while (dist < totalWayLength) {
                    targetDistances.add(dist);
                    dist += intervalMeters;
                }
            } else {
                int segmentCount = (int) Math.round(inputVal);
                if (segmentCount > 1) {
                    double stepMeters = totalWayLength / segmentCount;
                    for (int i = 1; i < segmentCount; i++) {
                        targetDistances.add(i * stepMeters);
                    }
                }
            }

            if (targetDistances.isEmpty()) continue;

            // Generate insertion points with applied error jitter
            List<LatLon> newPointsToInsert = new ArrayList<>();
            for (double targetDist : targetDistances) {
                // Apply random error jitter (+/- maxErrorMeters)
                double jitter = (rand.nextDouble() * 2.0 - 1.0) * maxErrorMeters;
                double adjustedDist = Math.max(0.1, Math.min(totalWayLength - 0.1, targetDist + jitter));

                LatLon pt = getPointAtDistance(currentNodes, cumDist, adjustedDist);
                if (pt != null) {
                    newPointsToInsert.add(pt);
                }
            }

            // Create Node objects and reconstruct the Way node list
            List<Node> finalNodeList = new ArrayList<>();
            int currentSegmentIndex = 0;

            for (LatLon insertPt : newPointsToInsert) {
                // Find which segment this point belongs to
                while (currentSegmentIndex < currentNodes.size() - 1) {
                    LatLon p1 = currentNodes.get(currentSegmentIndex).getCoor();
                    LatLon p2 = currentNodes.get(currentSegmentIndex + 1).getCoor();

                    if (!finalNodeList.contains(currentNodes.get(currentSegmentIndex))) {
                        finalNodeList.add(currentNodes.get(currentSegmentIndex));
                    }

                    // Check if point lies on segment or comes before p2
                    double distToP2 = insertPt.greatCircleDistance(p2);
                    double segLen = p1.greatCircleDistance(p2);

                    if (distToP2 <= segLen) {
                        Node newNode = new Node(insertPt);
                        commands.add(new AddCommand(dataSet, newNode));
                        finalNodeList.add(newNode);
                        break;
                    } else {
                        currentSegmentIndex++;
                    }
                }
            }

            // Add remaining original nodes
            for (int i = currentSegmentIndex; i < currentNodes.size(); i++) {
                if (!finalNodeList.contains(currentNodes.get(i))) {
                    finalNodeList.add(currentNodes.get(i));
                }
            }

            // Update way with new node sequence
            Way updatedWay = new Way(way);
            updatedWay.setNodes(finalNodeList);
            commands.add(new org.openstreetmap.josm.command.ChangeCommand(way, updatedWay));
        }

        if (!commands.isEmpty()) {
            UndoRedoHandler.getInstance().add(new SequenceCommand("Divide Way with Nodes", commands));
        }
    }

    private LatLon getPointAtDistance(List<Node> nodes, double[] cumDist, double targetDist) {
        for (int i = 0; i < nodes.size() - 1; i++) {
            if (targetDist >= cumDist[i] && targetDist <= cumDist[i + 1]) {
                double segmentLength = cumDist[i + 1] - cumDist[i];
                double t = (segmentLength == 0) ? 0 : (targetDist - cumDist[i]) / segmentLength;

                LatLon p1 = nodes.get(i).getCoor();
                LatLon p2 = nodes.get(i + 1).getCoor();

                double lat = p1.lat() + t * (p2.lat() - p1.lat());
                double lon = p1.lon() + t * (p2.lon() - p1.lon());

                return new LatLon(lat, lon);
            }
        }
        return null;
    }
}
