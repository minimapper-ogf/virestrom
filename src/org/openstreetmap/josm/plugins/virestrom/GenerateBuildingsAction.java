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
    // ==========================================
    private static final double SHORT_SIDE_RATIO_MIN = 3.50;
    private static final double SHORT_SIDE_RATIO_MAX = 4.20;

    private static final double LONG_SIDE_RATIO_MIN = 0.62;
    private static final double LONG_SIDE_RATIO_MAX = 0.80;

    private static final double SQUARE_RATIO_MIN = 0.90;
    private static final double SQUARE_RATIO_MAX = 1.10;
    
    // New customized Row House ratio profiles
    private static final double ROW_STANDARD_RATIO_MIN = 1.5;
    private static final double ROW_STANDARD_RATIO_MAX = 1.9;

    private static final double ROW_SQUARE_RATIO_MIN = 0.70;
    private static final double ROW_SQUARE_RATIO_MAX = 0.90;

    private static final double ROW_DEEP_RATIO_MIN = 2.1;
    private static final double ROW_DEEP_RATIO_MAX = 2.5;
    // ==========================================

    private static String lastUnitSelection = "Sq Feet";
    private static String lastSizeValue = "1400";
    
    private static String lastSetbackMin = "15"; 
    private static String lastSetbackMax = "35"; 
    
    private static String lastRotationLimit = "5.0"; 

    private static boolean lastAlignToRoad = true;

    private static boolean lastGenerateAlongRoadMode = false;
    private static String lastSpacingMin = "10";
    private static String lastSpacingMax = "25";
    private static String lastRoadSide = "Both"; 

    private static boolean lastUseShortSide = true;
    private static boolean lastUseLongSide = false;
    private static boolean lastUseSquare = false;
    
    private static boolean lastUseRowStandard = false;
    private static boolean lastUseRowSquare = false;
    private static boolean lastUseRowDeep = false;

    // New persistent toggle states
    private static boolean lastGenerateDriveways = false;
    private static boolean lastGenerateSheds = false;

    public GenerateBuildingsAction() {
        super("Generate Buildings", "building", "Generates procedural buildings inside selected areas or along roads",
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
                } else {
                    roads.add(way);
                }
            }
        }

        if (boundaries.isEmpty() && roads.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Please select at least one closed way (lot boundary) or an open way (road).",
                    "Generate Buildings", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Expanded UI Layout to fit Driveway and Shed config
        JPanel panel = new JPanel(new GridLayout(16, 2, 5, 5));
        JTextField sizeField = new JTextField(lastSizeValue);
        
        JTextField setbackMinField = new JTextField(lastSetbackMin, 5);
        JTextField setbackMaxField = new JTextField(lastSetbackMax, 5);
        JPanel setbackRangePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setbackRangePanel.add(setbackMinField);
        setbackRangePanel.add(new JLabel("-"));
        setbackRangePanel.add(setbackMaxField);

        JTextField rotationField = new JTextField(lastRotationLimit);
        
        JComboBox<String> unitBox = new JComboBox<>(new String[]{"Sq Meters", "Sq Feet"});
        unitBox.setSelectedItem(lastUnitSelection);
        JCheckBox alignRoadBox = new JCheckBox("Align to Nearest Road", lastAlignToRoad);

        JCheckBox alongRoadModeBox = new JCheckBox("Generate along Road (No Lots)", lastGenerateAlongRoadMode);
        JTextField spacingMinField = new JTextField(lastSpacingMin, 5);
        JTextField spacingMaxField = new JTextField(lastSpacingMax, 5);
        JPanel spacingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        spacingPanel.add(spacingMinField);
        spacingPanel.add(new JLabel("-"));
        spacingPanel.add(spacingMaxField);
        JComboBox<String> sideBox = new JComboBox<>(new String[]{"Both", "Left", "Right"});
        sideBox.setSelectedItem(lastRoadSide);

        JCheckBox shortSideCheck = new JCheckBox("Urban Short Side", lastUseShortSide);
        JCheckBox longSideCheck = new JCheckBox("Urban Long Side", lastUseLongSide);
        JCheckBox squareCheck = new JCheckBox("Suburban Square", lastUseSquare);
        
        JCheckBox rowStandardCheck = new JCheckBox("Row House - Standard", lastUseRowStandard);
        JCheckBox rowSquareCheck = new JCheckBox("Row House - Square", lastUseRowSquare);
        JCheckBox rowDeepCheck = new JCheckBox("Row House - Deep", lastUseRowDeep);

        JCheckBox drivewayCheck = new JCheckBox("Generate Driveways", lastGenerateDriveways);
        JCheckBox shedCheck = new JCheckBox("Generate Sheds in Back", lastGenerateSheds);

        // Helper event to check if any row house types are currently active
        Runnable updateInteractiveFields = () -> {
            boolean rowActive = rowStandardCheck.isSelected() || rowSquareCheck.isSelected() || rowDeepCheck.isSelected();
            boolean roadOnly = alongRoadModeBox.isSelected();
            
            if (rowActive) {
                rotationField.setEnabled(false);
                rotationField.setText("0.0");
                spacingMinField.setEnabled(false);
                spacingMaxField.setEnabled(false);
                drivewayCheck.setEnabled(false);
                drivewayCheck.setSelected(false);
                shedCheck.setEnabled(false);
                shedCheck.setSelected(false);
                
                // Clear out independent building shapes
                shortSideCheck.setSelected(false);
                longSideCheck.setSelected(false);
                squareCheck.setSelected(false);
            } else {
                rotationField.setEnabled(true);
                rotationField.setText(lastRotationLimit);
                spacingMinField.setEnabled(roadOnly);
                spacingMaxField.setEnabled(roadOnly);
                drivewayCheck.setEnabled(true);
                shedCheck.setEnabled(true);
            }
        };

        alongRoadModeBox.addItemListener(itemEvent -> {
            boolean active = alongRoadModeBox.isSelected();
            sideBox.setEnabled(active);
            alignRoadBox.setEnabled(!active); 
            updateInteractiveFields.run();
        });

        rowStandardCheck.addActionListener(actionEvent -> updateInteractiveFields.run());
        rowSquareCheck.addActionListener(actionEvent -> updateInteractiveFields.run());
        rowDeepCheck.addActionListener(actionEvent -> updateInteractiveFields.run());

        // Initial GUI layout loads
        updateInteractiveFields.run();
        sideBox.setEnabled(lastGenerateAlongRoadMode);
        if (lastGenerateAlongRoadMode) {
            alignRoadBox.setEnabled(false);
        }

        panel.add(new JLabel("Target Median Size:"));
        panel.add(sizeField);
        panel.add(new JLabel("Measurement Unit:"));
        panel.add(unitBox);
        panel.add(new JLabel("Setback Range (Min - Max):"));
        panel.add(setbackRangePanel);
        panel.add(new JLabel("Random Rotation Range (+/- Degrees):"));
        panel.add(rotationField);
        panel.add(new JLabel("Road-Only Generation:"));
        panel.add(alongRoadModeBox);
        panel.add(new JLabel("Building Spacing (Min - Max):"));
        panel.add(spacingPanel);
        panel.add(new JLabel("Placement Side of Road:"));
        panel.add(sideBox);
        panel.add(new JLabel("Alignment:"));
        panel.add(alignRoadBox);
        panel.add(new JLabel("Standard Forms:"));
        panel.add(shortSideCheck);
        panel.add(new JLabel(""));
        panel.add(longSideCheck);
        panel.add(new JLabel(""));
        panel.add(squareCheck);
        panel.add(new JLabel("Row House Forms:"));
        panel.add(rowStandardCheck);
        panel.add(new JLabel(""));
        panel.add(rowSquareCheck);
        panel.add(new JLabel(""));
        panel.add(rowDeepCheck);
        panel.add(new JLabel("Add-ons (Independent Only):"));
        panel.add(drivewayCheck);
        panel.add(new JLabel(""));
        panel.add(shedCheck);

        int result = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), panel,
                "Generate Buildings Configuration", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        lastSizeValue = sizeField.getText();
        lastSetbackMin = setbackMinField.getText();
        lastSetbackMax = setbackMaxField.getText();
        lastRotationLimit = rotationField.getText();
        lastUnitSelection = (String) unitBox.getSelectedItem();
        lastAlignToRoad = alignRoadBox.isSelected();
        lastGenerateAlongRoadMode = alongRoadModeBox.isSelected();
        lastSpacingMin = spacingMinField.getText();
        lastSpacingMax = spacingMaxField.getText();
        lastRoadSide = (String) sideBox.getSelectedItem();
        
        lastUseShortSide = shortSideCheck.isSelected();
        lastUseLongSide = longSideCheck.isSelected();
        lastUseSquare = squareCheck.isSelected();
        
        lastUseRowStandard = rowStandardCheck.isSelected();
        lastUseRowSquare = rowSquareCheck.isSelected();
        lastUseRowDeep = rowDeepCheck.isSelected();

        lastGenerateDriveways = drivewayCheck.isSelected();
        lastGenerateSheds = shedCheck.isSelected();

        double targetAreaMeters;
        double targetSetbackMinMeters;
        double targetSetbackMaxMeters;
        double maxRotationDegrees;
        double spacingMinMeters;
        double spacingMaxMeters;

        try {
            double parsedAreaInput = Double.parseDouble(lastSizeValue);
            double parsedSetbackMinInput = Double.parseDouble(lastSetbackMin);
            double parsedSetbackMaxInput = Double.parseDouble(lastSetbackMax);
            maxRotationDegrees = (lastUseRowStandard || lastUseRowSquare || lastUseRowDeep) ? 0.0 : Double.parseDouble(lastRotationLimit);
            double parsedSpacingMinInput = Double.parseDouble(lastSpacingMin);
            double parsedSpacingMaxInput = Double.parseDouble(lastSpacingMax);

            double conversionFactor = "Sq Feet".equals(lastUnitSelection) ? 0.3048 : 1.0;
            
            if ("Sq Feet".equals(lastUnitSelection)) {
                targetAreaMeters = parsedAreaInput * 0.092903;
            } else {
                targetAreaMeters = parsedAreaInput;
            }

            targetSetbackMinMeters = parsedSetbackMinInput * conversionFactor;
            targetSetbackMaxMeters = parsedSetbackMaxInput * conversionFactor;
            spacingMinMeters = parsedSpacingMinInput * conversionFactor;
            spacingMaxMeters = parsedSpacingMaxInput * conversionFactor;

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Invalid number entered.", "Generate Buildings", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (lastGenerateAlongRoadMode && roads.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Road-Only Mode selected, but no open roads are in your selection.",
                    "Generate Buildings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!lastGenerateAlongRoadMode && boundaries.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Lot Boundary Mode selected, but no closed ways are in your selection.",
                    "Generate Buildings", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<String> activeStyles = new ArrayList<>();
        List<String> activeRowStyles = new ArrayList<>();

        if (lastUseRowStandard) activeRowStyles.add("ROWHOUSE_STANDARD");
        if (lastUseRowSquare) activeRowStyles.add("ROWHOUSE_SQUARE");
        if (lastUseRowDeep) activeRowStyles.add("ROWHOUSE_DEEP");

        boolean isRowMode = !activeRowStyles.isEmpty();

        if (isRowMode) {
            activeStyles.addAll(activeRowStyles);
        } else {
            if (lastUseShortSide) activeStyles.add("SHORT");
            if (lastUseLongSide) activeStyles.add("LONG");
            if (lastUseSquare) activeStyles.add("SQUARE");
            if (activeStyles.isEmpty()) {
                activeStyles.add("SHORT");
            }
        }

        List<Command> commands = new ArrayList<>();
        Random rand = new Random();

        if (lastGenerateAlongRoadMode) {
            // ==========================================
            //  ROAD-ONLY GENERATION ENGINE
            // ==========================================
            List<List<LatLon>> newlyCreatedPolygons = new ArrayList<>();

            for (Way road : roads) {
                if (road.getNodesCount() < 2) continue;

                double[] cumDist = new double[road.getNodesCount()];
                cumDist[0] = 0.0;
                for (int i = 1; i < road.getNodesCount(); i++) {
                    cumDist[i] = cumDist[i - 1] + road.getNode(i - 1).getCoor().greatCircleDistance(road.getNode(i).getCoor());
                }
                double totalRoadLength = cumDist[cumDist.length - 1];

                double currentDist = rand.nextDouble() * (isRowMode ? 5.0 : spacingMaxMeters);

                while (currentDist < totalRoadLength) {
                    
                    if (isRowMode) {
                        String rowStyleForThisBlock = activeRowStyles.get(rand.nextInt(activeRowStyles.size()));
                        
                        double activeMinRatio;
                        if ("ROWHOUSE_SQUARE".equals(rowStyleForThisBlock)) {
                            activeMinRatio = ROW_SQUARE_RATIO_MIN;
                        } else if ("ROWHOUSE_DEEP".equals(rowStyleForThisBlock)) {
                            activeMinRatio = ROW_DEEP_RATIO_MIN;
                        } else {
                            activeMinRatio = ROW_STANDARD_RATIO_MIN;
                        }

                        double rowHouseWidth = Math.sqrt(targetAreaMeters / activeMinRatio);
                        
                        int numUnits = 4 + rand.nextInt(5); 
                        double totalRowLengthNeeded = rowHouseWidth * numUnits;

                        if (currentDist + totalRowLengthNeeded > totalRoadLength) {
                            numUnits = (int) Math.floor((totalRoadLength - currentDist) / rowHouseWidth);
                            if (numUnits < 2) break;
                            totalRowLengthNeeded = rowHouseWidth * numUnits;
                        }

                        List<String> sidesToGenerate = new ArrayList<>();
                        if ("Both".equals(lastRoadSide)) {
                            sidesToGenerate.add("Left");
                            sidesToGenerate.add("Right");
                        } else {
                            sidesToGenerate.add(lastRoadSide);
                        }

                        for (String side : sidesToGenerate) {
                            double sharedSetbackDist = targetSetbackMinMeters + rand.nextDouble() * (targetSetbackMaxMeters - targetSetbackMinMeters);

                            // Calculate structural road angle at start of block to prevent wavy/overlapping staircasing
                            RoadPoint rowStartPt = getPointAtDistance(road, currentDist + (rowHouseWidth / 2.0), cumDist);
                            double continuousAngle = rowStartPt.angle;

                            double perpAngle = "Left".equals(side) ? continuousAngle + Math.PI / 2.0 : continuousAngle - Math.PI / 2.0;
                            double metersPerDegreeLat = 111000.0;
                            double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(rowStartPt.pt.lat()));

                            // Continuous Vector-Based Placement
                            for (int u = 0; u < numUnits; u++) {
                                double offsetAlongRow = (u - (numUnits - 1) / 2.0) * rowHouseWidth;

                                double centerLatOffset = (sharedSetbackDist * Math.sin(perpAngle) + offsetAlongRow * Math.sin(continuousAngle)) / metersPerDegreeLat;
                                double centerLonOffset = (sharedSetbackDist * Math.cos(perpAngle) + offsetAlongRow * Math.cos(continuousAngle)) / metersPerDegreeLon;

                                LatLon buildingCenter = new LatLon(rowStartPt.pt.lat() + centerLatOffset, rowStartPt.pt.lon() + centerLonOffset);

                                List<Node> buildingNodes = createTransitionZoneNodes(buildingCenter, targetAreaMeters, continuousAngle, rowStyleForThisBlock, rand);
                                if (buildingNodes.size() < 3) continue;

                                // Prevent overlaps against tagged elements & highway objects
                                if (intersectsExistingObjects(buildingNodes, dataSet, newlyCreatedPolygons)) {
                                    continue;
                                }

                                Way buildingWay = new Way();
                                List<LatLon> currentPolyCoords = new ArrayList<>();
                                for (Node node : buildingNodes) {
                                    commands.add(new AddCommand(dataSet, node));
                                    buildingWay.addNode(node);
                                    currentPolyCoords.add(node.getCoor());
                                }
                                buildingWay.addNode(buildingNodes.get(0));
                                buildingWay.put("tobetagged", "yes");
                                commands.add(new AddCommand(dataSet, buildingWay));
                                newlyCreatedPolygons.add(currentPolyCoords);
                            }
                        }
                        
                        currentDist += totalRowLengthNeeded + (15.0 * 0.3048); 
                        
                    } else {
                        // Standard independent house generation along road
                        String activeStyle = activeStyles.get(rand.nextInt(activeStyles.size()));
                        double variance = 0.90 + (1.10 - 0.90) * rand.nextDouble();
                        double currentTargetArea = targetAreaMeters * variance;
                        
                        double aspectRatio;
                        if ("SQUARE".equals(activeStyle)) {
                            aspectRatio = SQUARE_RATIO_MIN + (rand.nextDouble() * (SQUARE_RATIO_MAX - SQUARE_RATIO_MIN));
                        } else if ("LONG".equals(activeStyle)) {
                            aspectRatio = LONG_SIDE_RATIO_MIN + (rand.nextDouble() * (LONG_SIDE_RATIO_MAX - LONG_SIDE_RATIO_MIN));
                        } else {
                            aspectRatio = SHORT_SIDE_RATIO_MIN + (rand.nextDouble() * (SHORT_SIDE_RATIO_MAX - SHORT_SIDE_RATIO_MIN));
                        }
                        
                        double buildingWidth = Math.sqrt(currentTargetArea / aspectRatio);

                        if (currentDist + (buildingWidth / 2.0) > totalRoadLength) break;

                        RoadPoint roadPt = getPointAtDistance(road, currentDist, cumDist);
                        double finalHeading = roadPt.angle;
                        if (maxRotationDegrees > 0) {
                            double randomOffsetDegrees = (rand.nextDouble() * 2.0 - 1.0) * maxRotationDegrees;
                            finalHeading += Math.toRadians(randomOffsetDegrees);
                        }

                        List<String> sidesToGenerate = new ArrayList<>();
                        if ("Both".equals(lastRoadSide)) {
                            sidesToGenerate.add("Left");
                            sidesToGenerate.add("Right");
                        } else {
                            sidesToGenerate.add(lastRoadSide);
                        }

                        for (String side : sidesToGenerate) {
                            double setbackDist = targetSetbackMinMeters + rand.nextDouble() * (targetSetbackMaxMeters - targetSetbackMinMeters);
                            double perpAngle = "Left".equals(side) ? roadPt.angle + Math.PI / 2.0 : roadPt.angle - Math.PI / 2.0;

                            double metersPerDegreeLat = 111000.0;
                            double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(roadPt.pt.lat()));

                            double offsetLat = (setbackDist * Math.sin(perpAngle)) / metersPerDegreeLat;
                            double offsetLon = (setbackDist * Math.cos(perpAngle)) / metersPerDegreeLon;

                            LatLon buildingCenter = new LatLon(roadPt.pt.lat() + offsetLat, roadPt.pt.lon() + offsetLon);

                            List<Node> buildingNodes = createTransitionZoneNodes(buildingCenter, currentTargetArea, finalHeading, activeStyle, rand);
                            if (buildingNodes.size() < 3) continue;

                            // Prevent overlaps against tagged elements & highway objects
                            if (intersectsExistingObjects(buildingNodes, dataSet, newlyCreatedPolygons)) {
                                continue;
                            }

                            Way buildingWay = new Way();
                            List<LatLon> currentPolyCoords = new ArrayList<>();
                            for (Node node : buildingNodes) {
                                commands.add(new AddCommand(dataSet, node));
                                buildingWay.addNode(node);
                                currentPolyCoords.add(node.getCoor());
                            }
                            buildingWay.addNode(buildingNodes.get(0));
                            buildingWay.put("tobetagged", "yes");
                            commands.add(new AddCommand(dataSet, buildingWay));
                            newlyCreatedPolygons.add(currentPolyCoords);

                            // Optional Addon: Procedural Driveway
                            if (lastGenerateDriveways) {
                                generateDriveway(dataSet, commands, roadPt.pt, buildingCenter, finalHeading, buildingWidth, setbackDist, rand);
                            }

                            // Optional Addon: Procedural Backyard Shed
                            if (lastGenerateSheds) {
                                generateBackyardShed(dataSet, commands, buildingCenter, finalHeading, perpAngle, rand);
                            }
                        }

                        double gap = spacingMinMeters + rand.nextDouble() * (spacingMaxMeters - spacingMinMeters);
                        currentDist += buildingWidth + gap;
                    }
                }
            }
        } else {
            // ==========================================
            //  ORIGINAL GRID LOT GENERATION ENGINE
            // ==========================================
            for (Way boundary : boundaries) {
                LatLon center = getCentroid(boundary);

                double variance = isRowMode ? 1.0 : (0.90 + (1.10 - 0.90) * rand.nextDouble());
                double currentTargetArea = targetAreaMeters * variance;

                double headingRotation;
                if (lastAlignToRoad && !roads.isEmpty()) {
                    headingRotation = calculateRoadAngle(center, roads);
                } else {
                    headingRotation = rand.nextDouble() * 2 * Math.PI;
                }

                if (maxRotationDegrees > 0 && !isRowMode) {
                    double randomOffsetDegrees = (rand.nextDouble() * 2.0 - 1.0) * maxRotationDegrees;
                    headingRotation += Math.toRadians(randomOffsetDegrees);
                }

                String activeStyle = activeStyles.get(rand.nextInt(activeStyles.size()));
                List<Node> buildingNodes = createTransitionZoneNodes(center, currentTargetArea, headingRotation, activeStyle, rand);

                if (buildingNodes.size() < 3) continue;

                double finalSetback = 0.0;
                if (lastAlignToRoad && !roads.isEmpty()) {
                    LatLon shiftVector = calculateControlledSetbackShift(center, roads, targetSetbackMinMeters, targetSetbackMaxMeters, rand);
                    for (Node node : buildingNodes) {
                        double newLat = node.getCoor().lat() + shiftVector.lat();
                        double newLon = node.getCoor().lon() + shiftVector.lon();
                        node.setCoor(new LatLon(newLat, newLon));
                    }
                    
                    // Recompute precise shifted center
                    center = getCentroidOfNodes(buildingNodes);
                    finalSetback = getDistanceToNearestRoad(center, roads);
                }

                Way buildingWay = new Way();
                for (Node node : buildingNodes) {
                    commands.add(new AddCommand(dataSet, node));
                    buildingWay.addNode(node);
                }
                buildingWay.addNode(buildingNodes.get(0));
                buildingWay.put("tobetagged", "yes");
                commands.add(new AddCommand(dataSet, buildingWay));

                if (!isRowMode && lastGenerateDriveways && !roads.isEmpty()) {
                    LatLon nearestRoadPt = getNearestRoadPoint(center, roads);
                    double buildingWidthEstimated = Math.sqrt(currentTargetArea);
                    generateDriveway(dataSet, commands, nearestRoadPt, center, headingRotation, buildingWidthEstimated, finalSetback, rand);
                }

                if (!isRowMode && lastGenerateSheds && !roads.isEmpty()) {
                    double roadAngle = calculateRoadAngle(center, roads);
                    // Determine setback direction vector pointing away from nearest road
                    LatLon nearestRoadPt = getNearestRoadPoint(center, roads);
                    double dLat = center.lat() - nearestRoadPt.lat();
                    double dLon = center.lon() - nearestRoadPt.lon();
                    double perpAngle = Math.atan2(dLat, dLon);
                    generateBackyardShed(dataSet, commands, center, headingRotation, perpAngle, rand);
                }
            }
        }

        UndoRedoHandler.getInstance().add(new SequenceCommand("Generate Buildings", commands));
        dataSet.setSelected(selection);
    }

    // ==========================================
    //   PROCEDURAL ADD-ON GENERATOR ENGINE
    // ==========================================

    private void generateDriveway(DataSet dataSet, List<Command> commands, LatLon roadPt, LatLon houseCenter, double houseHeading, double houseWidth, double setbackMeters, Random rand) {
        if (setbackMeters <= 2.0) return; // Too close to road to make a realistic driveway

        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(houseCenter.lat()));

        // Offset the parking spot to either the left or right side of the home
        boolean parkOnLeft = rand.nextBoolean();
        double sideOffsetDist = (houseWidth / 2.0) + (1.5 + rand.nextDouble() * 2.0); // 1.5 - 3.5 meters side clearance
        if (parkOnLeft) sideOffsetDist = -sideOffsetDist;

        // Perpendicular offset angle from house heading
        double perpOffsetAngle = houseHeading + Math.PI / 2.0;

        double offsetLat = (sideOffsetDist * Math.sin(perpOffsetAngle)) / metersPerDegreeLat;
        double offsetLon = (sideOffsetDist * Math.cos(perpOffsetAngle)) / metersPerDegreeLon;

        // Terminal parking pad node coordinates next to house
        LatLon drivewayEnd = new LatLon(houseCenter.lat() + offsetLat, houseCenter.lon() + offsetLon);

        List<Node> drivewayNodes = new ArrayList<>();
        
        // Start node on the public street
        Node startNode = new Node(roadPt);
        commands.add(new AddCommand(dataSet, startNode));
        drivewayNodes.add(startNode);

        double lengthFeet = setbackMeters / 0.3048;

        if (lengthFeet > 70.0) {
            // SPECIAL HIGH-FIDELITY SHAPE: Multi-node curved estate driveway
            int numInterNodes = 4 + rand.nextInt(4); // 4 to 7 intermediate winding nodes
            
            // Random sway strength factor
            double curvatureStrength = 3.0 + rand.nextDouble() * 6.0; 
            boolean curveDirection = rand.nextBoolean();

            for (int i = 1; i < numInterNodes; i++) {
                double t = (double) i / numInterNodes;
                
                // Linear step position
                double interpLat = roadPt.lat() + t * (drivewayEnd.lat() - roadPt.lat());
                double interpLon = roadPt.lon() + t * (drivewayEnd.lon() - roadPt.lon());

                // Apply organic S-curve offset perpendicular to trajectory
                double sineWave = Math.sin(t * Math.PI);
                double offsetAmount = sineWave * curvatureStrength;
                if (!curveDirection) offsetAmount = -offsetAmount;

                double swayAngle = Math.atan2(drivewayEnd.lat() - roadPt.lat(), drivewayEnd.lon() - roadPt.lon()) + Math.PI / 2.0;
                
                double swayLat = (offsetAmount * Math.sin(swayAngle)) / metersPerDegreeLat;
                double swayLon = (offsetAmount * Math.cos(swayAngle)) / metersPerDegreeLon;

                Node interNode = new Node(new LatLon(interpLat + swayLat, interpLon + swayLon));
                commands.add(new AddCommand(dataSet, interNode));
                drivewayNodes.add(interNode);
            }
        } else {
            // STANDARD SHORT DRIVEWAY: 2-3 nodes (Straight, or single angled elbow)
            if (rand.nextBoolean()) {
                // Add an intermediate node to make a nice elbow bend
                double interpLat = roadPt.lat() + 0.5 * (drivewayEnd.lat() - roadPt.lat());
                double interpLon = roadPt.lon() + 0.5 * (drivewayEnd.lon() - roadPt.lon());
                
                // Slight random wiggle offset
                double wiggleDist = (rand.nextDouble() * 2.0 - 1.0);
                double wiggleAngle = perpOffsetAngle;
                
                double wiggleLat = (wiggleDist * Math.sin(wiggleAngle)) / metersPerDegreeLat;
                double wiggleLon = (wiggleDist * Math.cos(wiggleAngle)) / metersPerDegreeLon;

                Node elbowNode = new Node(new LatLon(interpLat + wiggleLat, interpLon + wiggleLon));
                commands.add(new AddCommand(dataSet, elbowNode));
                drivewayNodes.add(elbowNode);
            }
        }

        // Terminal node
        Node endNode = new Node(drivewayEnd);
        commands.add(new AddCommand(dataSet, endNode));
        drivewayNodes.add(endNode);

        Way drivewayWay = new Way();
        for (Node n : drivewayNodes) {
            drivewayWay.addNode(n);
        }

        // Apply standardized OpenStreetMap tags for driveways
        drivewayWay.put("highway", "service");
        drivewayWay.put("lanes", "1");
        drivewayWay.put("service", "driveway");
        drivewayWay.put("width", "4");

        commands.add(new AddCommand(dataSet, drivewayWay));
    }

    private void generateBackyardShed(DataSet dataSet, List<Command> commands, LatLon houseCenter, double houseHeading, double setbackPerpAngle, Random rand) {
        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(houseCenter.lat()));

        // Offset the shed behind the house (further down the road-setback vector)
        double yardDepthOffset = 10.0 + rand.nextDouble() * 12.0; // 10 to 22 meters deep in the yard
        double sideYardWiggle = (rand.nextDouble() * 2.0 - 1.0) * 4.0; // Side adjustment inside yard

        double backyardLat = (yardDepthOffset * Math.sin(setbackPerpAngle) + sideYardWiggle * Math.sin(setbackPerpAngle + Math.PI/2.0)) / metersPerDegreeLat;
        double backyardLon = (yardDepthOffset * Math.cos(setbackPerpAngle) + sideYardWiggle * Math.cos(setbackPerpAngle + Math.PI/2.0)) / metersPerDegreeLon;

        LatLon shedCenter = new LatLon(houseCenter.lat() + backyardLat, houseCenter.lon() + backyardLon);

        // Typical small backyard utility shed sizes: 8x10ft (~2.4x3m) up to 12x14ft (~3.6x4.2m)
        double shedWidth = (8.0 + rand.nextDouble() * 4.0) * 0.3048;
        double shedHeight = (10.0 + rand.nextDouble() * 4.0) * 0.3048;

        double halfW = shedWidth / 2.0;
        double halfH = shedHeight / 2.0;

        // Base rectangular footprints
        List<Point2D> perimeter = new ArrayList<>();
        perimeter.add(new Point2D(-halfW, -halfH));
        perimeter.add(new Point2D(halfW, -halfH));
        perimeter.add(new Point2D(halfW, halfH));
        perimeter.add(new Point2D(-halfW, halfH));

        List<Node> shedNodes = new ArrayList<>();
        double cosR = Math.cos(houseHeading);
        double sinR = Math.sin(houseHeading);

        for (Point2D pt : perimeter) {
            double degX = pt.x / metersPerDegreeLon;
            double degY = pt.y / metersPerDegreeLat;

            double rotatedLon = degX * cosR - degY * sinR;
            double rotatedLat = degX * sinR + degY * cosR;

            Node shedNode = new Node(new LatLon(shedCenter.lat() + rotatedLat, shedCenter.lon() + rotatedLon));
            commands.add(new AddCommand(dataSet, shedNode));
            shedNodes.add(shedNode);
        }

        Way shedWay = new Way();
        for (Node n : shedNodes) {
            shedWay.addNode(n);
        }
        shedWay.addNode(shedNodes.get(0)); // Close way
        shedWay.put("building", "shed");

        commands.add(new AddCommand(dataSet, shedWay));
    }

    // ==========================================
    //   COLLISION PREVENTATIVE MAPPING METHODS
    // ==========================================

    private boolean intersectsExistingObjects(List<Node> newBuildingNodes, DataSet dataSet, List<List<LatLon>> newlyCreatedPolygons) {
        // Query database boundaries to inspect existing structures
        for (Way way : dataSet.getWays()) {
            if (way.hasKey("tobetagged") || way.hasKey("building") || way.hasKey("highway")) {
                if (way.getNodesCount() < 2) continue;
                List<LatLon> wayCoords = new ArrayList<>();
                for (Node n : way.getNodes()) {
                    wayCoords.add(n.getCoor());
                }
                if (intersectsWay(newBuildingNodes, wayCoords, way.isClosed())) {
                    return true;
                }
            }
        }
        // Query the local array containing geometries created in this process session
        for (List<LatLon> poly : newlyCreatedPolygons) {
            if (intersectsWay(newBuildingNodes, poly, true)) {
                return true;
            }
        }
        return false;
    }

    private boolean intersectsWay(List<Node> polyNodes, List<LatLon> wayCoords, boolean isClosed) {
        // Perform an optimized quick-rejection Bounding Box Sweep Check
        double minX1 = Double.MAX_VALUE, maxX1 = -Double.MAX_VALUE;
        double minY1 = Double.MAX_VALUE, maxY1 = -Double.MAX_VALUE;
        for (Node n : polyNodes) {
            double x = n.getCoor().lon();
            double y = n.getCoor().lat();
            if (x < minX1) minX1 = x;
            if (x > maxX1) maxX1 = x;
            if (y < minY1) minY1 = y;
            if (y > maxY1) maxY1 = y;
        }
        double minX2 = Double.MAX_VALUE, maxX2 = -Double.MAX_VALUE;
        double minY2 = Double.MAX_VALUE, maxY2 = -Double.MAX_VALUE;
        for (LatLon c : wayCoords) {
            double x = c.lon();
            double y = c.lat();
            if (x < minX2) minX2 = x;
            if (x > maxX2) maxX2 = x;
            if (y < minY2) minY2 = y;
            if (y > maxY2) maxY2 = y;
        }
        if (maxX1 < minX2 || maxX2 < minX1 || maxY1 < minY2 || maxY2 < minY1) {
            return false;
        }

        // Direct Segment Intersection Evaluation
        int n1 = polyNodes.size();
        int n2 = wayCoords.size();
        int segments2 = isClosed ? n2 : n2 - 1;

        for (int i = 0; i < n1; i++) {
            LatLon a1 = polyNodes.get(i).getCoor();
            LatLon b1 = polyNodes.get((i + 1) % n1).getCoor();
            for (int j = 0; j < segments2; j++) {
                LatLon a2 = wayCoords.get(j);
                LatLon b2 = wayCoords.get((j + 1) % n2);
                if (segmentsIntersect(a1, b1, a2, b2)) {
                    return true;
                }
            }
        }

        // Point-in-polygon containment checks
        if (isClosed && n2 >= 3) {
            if (isPointInPolygon(polyNodes.get(0).getCoor(), wayCoords)) {
                return true;
            }
        }
        for (LatLon c : wayCoords) {
            if (isPointInPolygon(c, polyNodes)) {
                return true;
            }
        }

        return false;
    }

    private boolean segmentsIntersect(LatLon p1, LatLon q1, LatLon p2, LatLon q2) {
        double o1 = ccw(p1, q1, p2);
        double o2 = ccw(p1, q1, q2);
        double o3 = ccw(p2, q2, p1);
        double o4 = ccw(p2, q2, q1);

        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) &&
            ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
            return true;
        }

        // Handle strict collinear cases
        if (o1 == 0 && onSegment(p1, p2, q1)) return true;
        if (o2 == 0 && onSegment(p1, q2, q1)) return true;
        if (o3 == 0 && onSegment(p2, p1, q2)) return true;
        if (o4 == 0 && onSegment(p2, q1, q2)) return true;

        return false;
    }

    private double ccw(LatLon p, LatLon q, LatLon r) {
        double val = (q.lat() - p.lat()) * (r.lon() - q.lon()) - (q.lon() - p.lon()) * (r.lat() - q.lat());
        if (val == 0) return 0;
        return (val > 0) ? 1 : -1;
    }

    private boolean onSegment(LatLon p, LatLon q, LatLon r) {
        return q.lon() <= Math.max(p.lon(), r.lon()) && q.lon() >= Math.min(p.lon(), r.lon()) &&
               q.lat() <= Math.max(p.lat(), r.lat()) && q.lat() >= Math.min(p.lat(), r.lat());
    }

    private boolean isPointInPolygon(LatLon pt, List<?> polygon) {
        int numNodes = polygon.size();
        if (numNodes < 3) return false;
        boolean inside = false;
        double px = pt.lon();
        double py = pt.lat();
        for (int i = 0, j = numNodes - 1; i < numNodes; j = i++) {
            LatLon pi = getLatLonFromList(polygon, i);
            LatLon pj = getLatLonFromList(polygon, j);
            if (pi == null || pj == null) continue;
            double ix = pi.lon();
            double iy = pi.lat();
            double jx = pj.lon();
            double jy = pj.lat();

            if (((iy > py) != (jy > py)) &&
                (px < (jx - ix) * (py - iy) / (jy - iy + 1e-12) + ix)) {
                inside = !inside;
            }
        }
        return inside;
    }

    private LatLon getLatLonFromList(List<?> list, int index) {
        Object obj = list.get(index);
        if (obj instanceof Node) {
            return ((Node) obj).getCoor();
        } else if (obj instanceof LatLon) {
            return (LatLon) obj;
        }
        return null;
    }

    // ==========================================
    //   ADDITIONAL MATH & DISTANCE HELPERS
    // ==========================================

    private LatLon getCentroidOfNodes(List<Node> nodes) {
        double lat = 0, lon = 0;
        for (Node n : nodes) {
            lat += n.getCoor().lat();
            lon += n.getCoor().lon();
        }
        return new LatLon(lat / nodes.size(), lon / nodes.size());
    }

    private double getDistanceToNearestRoad(LatLon center, List<Way> roads) {
        double closestDist = Double.MAX_VALUE;
        for (Way road : roads) {
            for (int i = 0; i < road.getNodesCount() - 1; i++) {
                Node n1 = road.getNode(i);
                Node n2 = road.getNode(i + 1);
                double midLat = (n1.getCoor().lat() + n2.getCoor().lat()) / 2.0;
                double midLon = (n1.getCoor().lon() + n2.getCoor().lon()) / 2.0;
                double dist = center.greatCircleDistance(new LatLon(midLat, midLon));
                if (dist < closestDist) closestDist = dist;
            }
        }
        return closestDist;
    }

    private LatLon getNearestRoadPoint(LatLon center, List<Way> roads) {
        double closestDist = Double.MAX_VALUE;
        LatLon bestPt = null;

        for (Way road : roads) {
            for (int i = 0; i < road.getNodesCount() - 1; i++) {
                Node n1 = road.getNode(i);
                Node n2 = road.getNode(i + 1);
                
                double rx = n2.getCoor().lon() - n1.getCoor().lon();
                double ry = n2.getCoor().lat() - n1.getCoor().lat();
                double rLenSq = rx * rx + ry * ry;
                if (rLenSq == 0) continue;

                double tx = center.lon() - n1.getCoor().lon();
                double ty = center.lat() - n1.getCoor().lat();
                double t = Math.max(0, Math.min(1, (tx * rx + ty * ry) / rLenSq));

                double closestLon = n1.getCoor().lon() + t * rx;
                double closestLat = n1.getCoor().lat() + t * ry;
                LatLon tempPt = new LatLon(closestLat, closestLon);

                double dist = center.greatCircleDistance(tempPt);
                if (dist < closestDist) {
                    closestDist = dist;
                    bestPt = tempPt;
                }
            }
        }
        return (bestPt != null) ? bestPt : center;
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

    private LatLon calculateControlledSetbackShift(LatLon center, List<Way> roads, double minSetback, double maxSetback, Random rand) {
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

        double finalDesiredSetback = minSetback + (rand.nextDouble() * (maxSetback - minSetback));
        finalDesiredSetback = Math.max(1.0, finalDesiredSetback);

        double shiftDistanceMeters = currentDistMeters - finalDesiredSetback;
        if (shiftDistanceMeters > (currentDistMeters - 1.0)) {
            shiftDistanceMeters = Math.max(0, currentDistMeters - 1.0);
        }

        double ratio = shiftDistanceMeters / currentDistMeters;
        return new LatLon(vecLat * ratio, vecLon * ratio);
    }

    private static class RoadPoint {
        LatLon pt;
        double angle;
        RoadPoint(LatLon pt, double angle) {
            this.pt = pt;
            this.angle = angle;
        }
    }

    private RoadPoint getPointAtDistance(Way road, double targetDist, double[] cumDist) {
        List<Node> nodes = road.getNodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            if (targetDist >= cumDist[i] && targetDist <= cumDist[i + 1]) {
                double segmentLength = cumDist[i + 1] - cumDist[i];
                double t = (segmentLength == 0) ? 0 : (targetDist - cumDist[i]) / segmentLength;

                LatLon p1 = nodes.get(i).getCoor();
                LatLon p2 = nodes.get(i + 1).getCoor();

                double lat = p1.lat() + t * (p2.lat() - p1.lat());
                double lon = p1.lon() + t * (p2.lon() - p1.lon());

                double dLat = p2.lat() - p1.lat();
                double dLon = p2.lon() - p1.lon();
                double angle = Math.atan2(dLat, dLon);

                return new RoadPoint(new LatLon(lat, lon), angle);
            }
        }
        LatLon lastPt = nodes.get(nodes.size() - 1).getCoor();
        return new RoadPoint(lastPt, 0.0);
    }

    private List<Node> createTransitionZoneNodes(LatLon center, double targetArea, double headingRotation, String style, Random rand) {
        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(center.lat()));

        double minSegmentLengthMeters = 8.0 * 0.3048;

        double aspectRatio;
        boolean isRowType = style.startsWith("ROWHOUSE");

        if ("SQUARE".equals(style)) {
            aspectRatio = SQUARE_RATIO_MIN + (rand.nextDouble() * (SQUARE_RATIO_MAX - SQUARE_RATIO_MIN));
        } else if ("LONG".equals(style)) {
            aspectRatio = LONG_SIDE_RATIO_MIN + (rand.nextDouble() * (LONG_SIDE_RATIO_MAX - LONG_SIDE_RATIO_MIN));
        } else if ("ROWHOUSE_SQUARE".equals(style)) {
            aspectRatio = ROW_SQUARE_RATIO_MIN; 
        } else if ("ROWHOUSE_DEEP".equals(style)) {
            aspectRatio = ROW_DEEP_RATIO_MIN; 
        } else if ("ROWHOUSE_STANDARD".equals(style)) {
            aspectRatio = ROW_STANDARD_RATIO_MIN; 
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
            
            if (isRowType) {
                if (edgeIndex != 0 && edgeIndex != 2) {
                    continue; 
                }
            }

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
