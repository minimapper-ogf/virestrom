package org.openstreetmap.josm.plugins.virestrom;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.*;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.data.UndoRedoHandler;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

public class GenerateBuildingsAction extends JosmAction {

    // ==========================================
    //   USER CONFIGURABLE DIMENSION RATIOS
    //   (Ratio = Width along road / Depth away from road)
    //   NOTE: values are inverted. It goes depth then width instead of the other way.
    // ==========================================
    // Target: 25ft wide x 60ft deep -> ~0.41
    private static final double SHORT_SIDE_RATIO_MIN = 3.50;
    private static final double SHORT_SIDE_RATIO_MAX = 4.20;

    // Target: 65ft wide x 30ft deep -> ~2.16
    private static final double LONG_SIDE_RATIO_MIN = 0.62;
    private static final double LONG_SIDE_RATIO_MAX = 0.80;

    // Target: Square shape close to 1:1 footprint
    private static final double SQUARE_RATIO_MIN = 0.90;
    private static final double SQUARE_RATIO_MAX = 1.10;
    // ==========================================

    private static String lastUnitSelection = "Sq Feet";
    private static String lastSizeValue = "1400";
    private static String lastSetbackValue = "25"; // Default 25 Ft / 7.6 Meters
    private static boolean lastAlignToRoad = true;

    private static boolean lastUseShortSide = true;
    private static boolean lastUseLongSide = false;
    private static boolean lastUseSquare = false;

public GenerateBuildingsAction() {
        super("Generate Buildings", "building", "Generates procedural buildings inside selected areas",
                Shortcut.registerShortcut("tools:generate_buildings", "Generate Buildings", KeyEvent.VK_Q, Shortcut.CTRL_SHIFT), true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) return;

        Collection<OsmPrimitive> selection = dataSet.getSelected();
        List<Way> boundaries = new ArrayList<>();
        List<Way> roads = new ArrayList<>();

        for (OsmPrimitive prim : selection) {
            if (prim instanceof Way) {
                Way way = (Way) prim;
                if (way.isClosed()) {
                    boundaries.add(way);
                } else if (way.hasKey("highway")) {
                    roads.add(way);
                }
            }
        }

        if (boundaries.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Please select at least one closed way (lot boundary).",
                    "Generate Buildings", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JPanel panel = new JPanel(new GridLayout(7, 2, 5, 5));
        JTextField sizeField = new JTextField(lastSizeValue);
        JTextField setbackField = new JTextField(lastSetbackValue);
        JComboBox<String> unitBox = new JComboBox<>(new String[]{"Sq Meters", "Sq Feet"});
        unitBox.setSelectedItem(lastUnitSelection);
        JCheckBox alignRoadBox = new JCheckBox("Align to Nearest Road", lastAlignToRoad);

        JCheckBox shortSideCheck = new JCheckBox("Urban Short Side", lastUseShortSide);
        JCheckBox longSideCheck = new JCheckBox("Urban Long Side", lastUseLongSide);
        JCheckBox squareCheck = new JCheckBox("Suburban Square", lastUseSquare);

        panel.add(new JLabel("Target Median Size:"));
        panel.add(sizeField);
        panel.add(new JLabel("Measurement Unit:"));
        panel.add(unitBox);
        panel.add(new JLabel("Avg Distance From Road (Ft/M):"));
        panel.add(setbackField);
        panel.add(new JLabel("Alignment:"));
        panel.add(alignRoadBox);
        panel.add(new JLabel("Allowed Building Forms:"));
        panel.add(shortSideCheck);
        panel.add(new JLabel(""));
        panel.add(longSideCheck);
        panel.add(new JLabel(""));
        panel.add(squareCheck);

        int result = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), panel,
                "Generate Buildings Configuration", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        lastSizeValue = sizeField.getText();
        lastSetbackValue = setbackField.getText();
        lastUnitSelection = (String) unitBox.getSelectedItem();
        lastAlignToRoad = alignRoadBox.isSelected();
        lastUseShortSide = shortSideCheck.isSelected();
        lastUseLongSide = longSideCheck.isSelected();
        lastUseSquare = squareCheck.isSelected();

        double targetAreaMeters;
        double targetSetbackMeters;
        try {
            double parsedAreaInput = Double.parseDouble(lastSizeValue);
            double parsedSetbackInput = Double.parseDouble(lastSetbackValue);
            if ("Sq Feet".equals(lastUnitSelection)) {
                targetAreaMeters = parsedAreaInput * 0.092903;
                targetSetbackMeters = parsedSetbackInput * 0.3048;
            } else {
                targetAreaMeters = parsedAreaInput;
                targetSetbackMeters = parsedSetbackInput;
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Invalid number entered.", "Generate Buildings", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<String> activeStyles = new ArrayList<>();
        if (lastUseShortSide) activeStyles.add("SHORT");
        if (lastUseLongSide) activeStyles.add("LONG");
        if (lastUseSquare) activeStyles.add("SQUARE");
        if (activeStyles.isEmpty()) {
            activeStyles.add("SHORT");
        }

        List<Command> commands = new ArrayList<>();
        Random rand = new Random();

        for (Way boundary : boundaries) {
            LatLon center = getCentroid(boundary);

            double variance = 0.90 + (1.10 - 0.90) * rand.nextDouble();
            double currentTargetArea = targetAreaMeters * variance;

            double headingRotation;
            if (lastAlignToRoad && !roads.isEmpty()) {
                headingRotation = calculateRoadAngle(center, roads);
            } else {
                headingRotation = rand.nextDouble() * 2 * Math.PI;
            }

            String activeStyle = activeStyles.get(rand.nextInt(activeStyles.size()));
            List<Node> buildingNodes = createTransitionZoneNodes(center, currentTargetArea, headingRotation, activeStyle, rand);

            if (buildingNodes.size() < 3) continue;

            if (lastAlignToRoad && !roads.isEmpty()) {
                LatLon shiftVector = calculateControlledSetbackShift(center, roads, targetSetbackMeters, rand);
                for (Node node : buildingNodes) {
                    double newLat = node.getCoor().lat() + shiftVector.lat();
                    double newLon = node.getCoor().lon() + shiftVector.lon();
                    node.setCoor(new LatLon(newLat, newLon));
                }
            }

            Way buildingWay = new Way();
            for (Node node : buildingNodes) {
                commands.add(new AddCommand(dataSet, node));
                buildingWay.addNode(node);
            }
            buildingWay.addNode(buildingNodes.get(0));
            buildingWay.put("tobetagged", "yes");
            commands.add(new AddCommand(dataSet, buildingWay));
        }

        UndoRedoHandler.getInstance().add(new SequenceCommand("Generate Buildings", commands));
        dataSet.setSelected(selection);
    }

    private LatLon getCentroid(Way way) {
        double lat = 0, lon = 0;
        int count = way.getNodesCount();
        for (int i = 0; i < count; i++) {
            lat += way.getNode(i).getCoor().lat();
            lon += way.getNode(i).getCoor().lon();
        }
        return new LatLon(lat / count, lon / count);
    }

    private double calculateRoadAngle(LatLon center, List<Way> roads) {
        double closestDist = Double.MAX_VALUE;
        Node bestNode1 = null;
        Node bestNode2 = null;

        for (Way road : roads) {
            for (int i = 0; i < road.getNodesCount() - 1; i++) {
                Node n1 = road.getNode(i);
                Node n2 = road.getNode(i + 1);
                double midLat = (n1.getCoor().lat() + n2.getCoor().lat()) / 2.0;
                double midLon = (n1.getCoor().lon() + n2.getCoor().lon()) / 2.0;
                double dist = center.greatCircleDistance(new LatLon(midLat, midLon));

                if (dist < closestDist) {
                    closestDist = dist;
                    bestNode1 = n1;
                    bestNode2 = n2;
                }
            }
        }

        if (bestNode1 == null || bestNode2 == null) return 0.0;

        double dLat = bestNode2.getCoor().lat() - bestNode1.getCoor().lat();
        double dLon = bestNode2.getCoor().lon() - bestNode1.getCoor().lon();
        return Math.atan2(dLat, dLon);
    }

    private LatLon calculateControlledSetbackShift(LatLon center, List<Way> roads, double targetSetbackMeters, Random rand) {
        double closestDist = Double.MAX_VALUE;
        Node bestNode1 = null;
        Node bestNode2 = null;

        for (Way road : roads) {
            for (int i = 0; i < road.getNodesCount() - 1; i++) {
                Node n1 = road.getNode(i);
                Node n2 = road.getNode(i + 1);
                double midLat = (n1.getCoor().lat() + n2.getCoor().lat()) / 2.0;
                double midLon = (n1.getCoor().lon() + n2.getCoor().lon()) / 2.0;
                double dist = center.greatCircleDistance(new LatLon(midLat, midLon));

                if (dist < closestDist) {
                    closestDist = dist;
                    bestNode1 = n1;
                    bestNode2 = n2;
                }
            }
        }

        if (bestNode1 == null || bestNode2 == null) return new LatLon(0, 0);

        double rx = bestNode2.getCoor().lon() - bestNode1.getCoor().lon();
        double ry = bestNode2.getCoor().lat() - bestNode1.getCoor().lat();
        double rLenSq = rx * rx + ry * ry;
        if (rLenSq == 0) return new LatLon(0, 0);

        double tx = center.lon() - bestNode1.getCoor().lon();
        double ty = center.lat() - bestNode1.getCoor().lat();
        double t = Math.max(0, Math.min(1, (tx * rx + ty * ry) / rLenSq));

        double closestLon = bestNode1.getCoor().lon() + t * rx;
        double closestLat = bestNode1.getCoor().lat() + t * ry;

        double vecLon = closestLon - center.lon();
        double vecLat = closestLat - center.lat();

        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(center.lat()));

        double currentDistMeters = Math.sqrt(Math.pow(vecLon * metersPerDegreeLon, 2) + Math.pow(vecLat * metersPerDegreeLat, 2));
        if (currentDistMeters == 0) return new LatLon(0, 0);

        double devFeet = (rand.nextDouble() * 8.0) - 4.0;
        double devMeters = devFeet * 0.3048;
        double finalDesiredSetback = Math.max(1.0, targetSetbackMeters + devMeters);

        double shiftDistanceMeters = currentDistMeters - finalDesiredSetback;
        if (shiftDistanceMeters > (currentDistMeters - 1.0)) {
            shiftDistanceMeters = Math.max(0, currentDistMeters - 1.0);
        }

        double ratio = shiftDistanceMeters / currentDistMeters;
        return new LatLon(vecLat * ratio, vecLon * ratio);
    }

    private List<Node> createTransitionZoneNodes(LatLon center, double targetArea, double headingRotation, String style, Random rand) {
        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(center.lat()));

        double minSegmentLengthMeters = 8.0 * 0.3048;

        double aspectRatio;
        if ("SQUARE".equals(style)) {
            aspectRatio = SQUARE_RATIO_MIN + (rand.nextDouble() * (SQUARE_RATIO_MAX - SQUARE_RATIO_MIN));
        } else if ("LONG".equals(style)) {
            aspectRatio = LONG_SIDE_RATIO_MIN + (rand.nextDouble() * (LONG_SIDE_RATIO_MAX - LONG_SIDE_RATIO_MIN));
        } else {
            aspectRatio = SHORT_SIDE_RATIO_MIN + (rand.nextDouble() * (SHORT_SIDE_RATIO_MAX - SHORT_SIDE_RATIO_MIN));
        }

        double baseWidthMeters = Math.sqrt(targetArea / aspectRatio);
        double baseHeightMeters = targetArea / baseWidthMeters;

        double halfW = baseWidthMeters / 2.0;
        double halfH = baseHeightMeters / 2.0;

        List<Point2D> perimeter = new ArrayList<>();
        perimeter.add(new Point2D(-halfW, -halfH));
        perimeter.add(new Point2D(halfW, -halfH));
        perimeter.add(new Point2D(halfW, halfH));
        perimeter.add(new Point2D(-halfW, halfH));

        int totalCuts = rand.nextInt(4) + 1;
        double cutScaleFactor = "LONG".equals(style) ? 0.45 : 0.60;

        for (int c = 0; c < totalCuts; c++) {
            if (perimeter.size() < 4) break;

            int edgeIndex = rand.nextInt(perimeter.size());
            Point2D p1 = perimeter.get(edgeIndex);
            Point2D p2 = perimeter.get((edgeIndex + 1) % perimeter.size());

            double edgeDx = p2.x - p1.x;
            double edgeDy = p2.y - p1.y;
            double edgeLength = Math.sqrt(edgeDx * edgeDx + edgeDy * edgeDy);

            if (edgeLength < (minSegmentLengthMeters * 3.0)) continue;

            double cutDepthMeters = ((2.0 + rand.nextDouble() * 8.0) * 0.3048) * cutScaleFactor;
            double maxPossibleCutLength = edgeLength - (minSegmentLengthMeters * 2.0);
            if (maxPossibleCutLength <= minSegmentLengthMeters) continue;

            double cutLengthMeters = (minSegmentLengthMeters + rand.nextDouble() * (maxPossibleCutLength - minSegmentLengthMeters)) * cutScaleFactor;
            if (cutLengthMeters < minSegmentLengthMeters) {
                cutLengthMeters = minSegmentLengthMeters;
            }

            double availableBufferSpace = edgeLength - cutLengthMeters - (minSegmentLengthMeters * 2.0);
            double startOffset = minSegmentLengthMeters + (availableBufferSpace > 0 ? rand.nextDouble() * availableBufferSpace : 0);

            double uX = edgeDx / edgeLength;
            double uY = edgeDy / edgeLength;

            Point2D cutStart = new Point2D(p1.x + uX * startOffset, p1.y + uY * startOffset);
            Point2D cutEnd = new Point2D(cutStart.x + uX * cutLengthMeters, cutStart.y + uY * cutLengthMeters);

            double nX = -uY;
            double nY = uX;

            boolean makeCutOut = "LONG".equals(style) ? rand.nextDouble() < 0.85 : rand.nextBoolean();

            if (makeCutOut) {
                double dotProduct = (cutStart.x * nX + cutStart.y * nY);
                if (dotProduct < 0) {
                    nX = -nX;
                    nY = -nY;
                }
            } else {
                double dotProduct = (cutStart.x * nX + cutStart.y * nY);
                if (dotProduct > 0) {
                    nX = -nX;
                    nY = -nY;
                }
            }

            Point2D cutStepIn = new Point2D(cutStart.x + nX * cutDepthMeters, cutStart.y + nY * cutDepthMeters);
            Point2D cutStepOut = new Point2D(cutEnd.x + nX * cutDepthMeters, cutEnd.y + nY * cutDepthMeters);

            List<Point2D> nextPerimeter = new ArrayList<>();
            for (int i = 0; i < perimeter.size(); i++) {
                nextPerimeter.add(perimeter.get(i));
                if (i == edgeIndex) {
                    nextPerimeter.add(cutStart);
                    nextPerimeter.add(cutStepIn);
                    nextPerimeter.add(cutStepOut);
                    nextPerimeter.add(cutEnd);
                }
            }
            perimeter = nextPerimeter;
        }

        List<Node> nodes = new ArrayList<>();
        double cosR = Math.cos(headingRotation);
        double sinR = Math.sin(headingRotation);

        for (Point2D pt : perimeter) {
            double degX = pt.x / metersPerDegreeLon;
            double degY = pt.y / metersPerDegreeLat;

            double rotatedLon = degX * cosR - degY * sinR;
            double rotatedLat = degX * sinR + degY * cosR;

            nodes.add(new Node(new LatLon(center.lat() + rotatedLat, center.lon() + rotatedLon)));
        }

        return nodes;
    }

    private static class Point2D {
        double x, y;
        Point2D(double x, double y) { this.x = x; this.y = y; }
    }
}
