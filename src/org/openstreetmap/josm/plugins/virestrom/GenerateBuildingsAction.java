package org.openstreetmap.josm.plugins.virestrom;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.*;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.spi.preferences.Config;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

public class GenerateBuildingsAction extends JosmAction {

    // ==========================================
    //   DYNAMIC BUILDING PROFILE DEFINITION
    // ==========================================
    public static class BuildingProfile {
        public String name;
        public double minRatio;
        public double maxRatio;
        public double insetFrequency; // 0.0 to 1.0
        public double minInsetDistance; // Minimum distance for insets/outsets in feet or meters
        public double[] allowedAngles;
        public boolean enabled = true;

        public BuildingProfile(String name, double minRatio, double maxRatio, double insetFrequency, double minInsetDistance, double[] allowedAngles) {
            this.name = name;
            this.minRatio = minRatio;
            this.maxRatio = maxRatio;
            this.insetFrequency = insetFrequency;
            this.minInsetDistance = minInsetDistance;
            this.allowedAngles = allowedAngles;
            this.enabled = true;
        }

        // Helper to serialize profile into a single preference string
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(";").append(minRatio).append(";").append(maxRatio).append(";")
            .append(insetFrequency).append(";").append(minInsetDistance).append(";").append(enabled).append(";");
            for (int i = 0; i < allowedAngles.length; i++) {
                sb.append(allowedAngles[i]).append(i == allowedAngles.length - 1 ? "" : ",");
            }
            return sb.toString();
        }

        // Helper to deserialize profile from preference string
        public static BuildingProfile deserialize(String data) {
            try {
                String[] parts = data.split(";");
                String name = parts[0];
                double minR = Double.parseDouble(parts[1]);
                double maxR = Double.parseDouble(parts[2]);
                double freq = Double.parseDouble(parts[3]);
                double minDist = Double.parseDouble(parts[4]);
                boolean enabled = Boolean.parseBoolean(parts[5]);

                String[] angParts = parts[6].split(",");
                double[] angles = new double[angParts.length];
                for (int i = 0; i < angParts.length; i++) {
                    angles[i] = Double.parseDouble(angParts[i]);
                }
                BuildingProfile prof = new BuildingProfile(name, minR, maxR, freq, minDist, angles);
                prof.enabled = enabled;
                return prof;
            } catch (Exception e) {
                return null; // Fallback handling
            }
        }

        @Override
        public String toString() {
            return String.format("%s (Ratio: %.2f - %.2f | Insets: %.0f%%)", name, minRatio, maxRatio, insetFrequency * 100);
        }
    }

    // Default profiles initialized once per session and modified globally
    private List<BuildingProfile> loadProfiles() {
        List<String> rawProfiles = Config.getPref().getList("virestrom.building_profiles", null);
        List<BuildingProfile> profiles = new ArrayList<>();

        if (rawProfiles != null && !rawProfiles.isEmpty()) {
            for (String s : rawProfiles) {
                BuildingProfile p = BuildingProfile.deserialize(s);
                if (p != null) profiles.add(p);
            }
        }

        // Fallback defaults if no saved preferences exist
        if (profiles.isEmpty()) {
            profiles.add(new BuildingProfile("Deep", 2.40, 3.00, 0.80, 8.0, new double[]{Math.PI / 2.0}));
            profiles.add(new BuildingProfile("60s", 0.62, 0.80, 0.45, 6.0, new double[]{Math.PI / 2.0}));
            profiles.add(new BuildingProfile("Square", 0.90, 1.10, 0.30, 8.0, new double[]{Math.PI / 2.0, Math.PI / 4.0}));
        }
        return profiles;
    }

    // Save profiles back to JOSM preferences
    private void saveProfiles(List<BuildingProfile> profiles) {
        List<String> serialized = new ArrayList<>();
        for (BuildingProfile p : profiles) {
            serialized.add(p.serialize());
        }
        Config.getPref().putList("virestrom.building_profiles", serialized);
    }

    private final List<BuildingProfile> sessionProfiles = loadProfiles();


    // Persistent state across runs in the same JOSM session
    private static String lastUnitSelection = "Sq Feet";
    private static String lastSizeValue = "1400";
    private static String lastSetbackMin = "70";
    private static String lastSetbackMax = "100";
    private static String lastRotationLimit = "5.0";

    private static boolean lastAlignToRoad = true;
    private static boolean lastGenerateAlongRoadMode = true;
    private static String lastSpacingMin = "55";
    private static String lastSpacingMax = "80";
    private static String lastRoadSide = "Both";

    private static boolean lastMakeRowHouses = false;
    private static boolean lastGenerateDriveways = false;
    private static boolean lastGenerateSheds = false;

    public GenerateBuildingsAction() {
        super("Generate Buildings", "building", "Generates procedural buildings inside selected areas or along roads",
                Shortcut.registerShortcut("tools:generate_buildings", "Generate Buildings", KeyEvent.VK_Q, Shortcut.CTRL_SHIFT), true);
    }

    // Custom List Cell Renderer for Checkbox + Label
    private static class CheckBoxListCellRenderer extends JCheckBox implements ListCellRenderer<BuildingProfile> {
        @Override
        public Component getListCellRendererComponent(JList<? extends BuildingProfile> list, BuildingProfile value, int index, boolean isSelected, boolean cellHasFocus) {
            setSelected(value.enabled);
            setText(value.toString());
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            setEnabled(list.isEnabled());
            setFont(list.getFont());
            setOpaque(true);
            return this;
        }
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

        // UI PANEL CONFIGURATION
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        JPanel settingsPanel = new JPanel(new GridLayout(10, 2, 5, 5));

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

        JCheckBox makeRowHousesBox = new JCheckBox("Make All Selected Types Row Houses", lastMakeRowHouses);
        JCheckBox drivewayCheck = new JCheckBox("Generate Driveways", lastGenerateDriveways);
        JCheckBox shedCheck = new JCheckBox("Generate Sheds in Back", lastGenerateSheds);

        // Building Profiles List Model
        DefaultListModel<BuildingProfile> profileListModel = new DefaultListModel<>();
        for (BuildingProfile p : sessionProfiles) {
            profileListModel.addElement(p);
        }

        JList<BuildingProfile> profileJList = new JList<>(profileListModel);
        profileJList.setCellRenderer(new CheckBoxListCellRenderer());
        profileJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Click handler to toggle checkbox on single click & open Edit Dialog on double click
        profileJList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                int index = profileJList.locationToIndex(evt.getPoint());
                if (index != -1) {
                    BuildingProfile profile = profileListModel.getElementAt(index);
                    if (evt.getClickCount() == 1) {
                        profile.enabled = !profile.enabled;
                        profileJList.repaint(profileJList.getCellBounds(index, index));
                    } else if (evt.getClickCount() == 2) {
                        showEditProfileDialog(profile, profileJList);
                    }
                }
            }
        });

        JPanel profileButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton addProfileBtn = new JButton("+ Add Type");
        JButton removeProfileBtn = new JButton("- Remove Selected");
        profileButtonPanel.add(addProfileBtn);
        profileButtonPanel.add(removeProfileBtn);

        // Add Profile Event
        addProfileBtn.addActionListener(evt -> {
            BuildingProfile newProf = new BuildingProfile("Custom Form", 1.2, 1.8, 0.5, 8.0, new double[]{Math.PI / 2.0, Math.PI / 4.0});
            if (showEditProfileDialog(newProf, null)) {
                sessionProfiles.add(newProf);
                profileListModel.addElement(newProf);
                saveProfiles(sessionProfiles); // Save configuration
            }
        });

        // Remove Profile Event
        removeProfileBtn.addActionListener(evt -> {
            BuildingProfile selected = profileJList.getSelectedValue();
            if (selected != null) {
                sessionProfiles.remove(selected);
                profileListModel.removeElement(selected);
                saveProfiles(sessionProfiles); // Save configuration
            }
        });

        // Interactive field updates
        Runnable updateInteractiveFields = () -> {
            boolean rowActive = makeRowHousesBox.isSelected();
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

        makeRowHousesBox.addActionListener(actionEvent -> updateInteractiveFields.run());

        updateInteractiveFields.run();
        sideBox.setEnabled(lastGenerateAlongRoadMode);
        if (lastGenerateAlongRoadMode) {
            alignRoadBox.setEnabled(false);
        }

        settingsPanel.add(new JLabel("Target Median Size:"));
        settingsPanel.add(sizeField);
        settingsPanel.add(new JLabel("Measurement Unit:"));
        settingsPanel.add(unitBox);
        settingsPanel.add(new JLabel("Setback Range (Min - Max):"));
        settingsPanel.add(setbackRangePanel);
        settingsPanel.add(new JLabel("Random Rotation Range (+/- Degrees):"));
        settingsPanel.add(rotationField);
        settingsPanel.add(new JLabel("Road-Only Generation:"));
        settingsPanel.add(alongRoadModeBox);
        settingsPanel.add(new JLabel("Building Spacing (Min - Max):"));
        settingsPanel.add(spacingPanel);
        settingsPanel.add(new JLabel("Placement Side of Road:"));
        settingsPanel.add(sideBox);
        settingsPanel.add(new JLabel("Alignment:"));
        settingsPanel.add(alignRoadBox);
        settingsPanel.add(new JLabel("Add-ons (Independent Only):"));
        settingsPanel.add(drivewayCheck);
        settingsPanel.add(new JLabel(""));
        settingsPanel.add(shedCheck);

        JPanel profileSectionPanel = new JPanel(new BorderLayout(5, 5));
        profileSectionPanel.setBorder(BorderFactory.createTitledBorder("Building Forms"));
        profileSectionPanel.add(new JScrollPane(profileJList), BorderLayout.CENTER);
        profileSectionPanel.add(profileButtonPanel, BorderLayout.NORTH);
        profileSectionPanel.add(makeRowHousesBox, BorderLayout.SOUTH);

        mainPanel.add(settingsPanel, BorderLayout.NORTH);
        mainPanel.add(profileSectionPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), mainPanel,
                "Generate Buildings Configuration", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        // Save session persistent states
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

        lastMakeRowHouses = makeRowHousesBox.isSelected();
        lastGenerateDriveways = drivewayCheck.isSelected();
        lastGenerateSheds = shedCheck.isSelected();

        // Extract active forms checked in list UI
        List<BuildingProfile> activePool = new ArrayList<>();
        for (int i = 0; i < profileListModel.getSize(); i++) {
            BuildingProfile prof = profileListModel.getElementAt(i);
            if (prof.enabled) {
                activePool.add(prof);
            }
        }

        if (activePool.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Please check/enable at least one Building Type form.", "Generate Buildings", JOptionPane.WARNING_MESSAGE);
            return;
        }

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
            maxRotationDegrees = lastMakeRowHouses ? 0.0 : Double.parseDouble(lastRotationLimit);
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

        List<Command> commands = new ArrayList<>();
        Random rand = new Random();

        if (lastGenerateAlongRoadMode) {
            // ROAD-ONLY GENERATION ENGINE
            List<List<LatLon>> newlyCreatedPolygons = new ArrayList<>();

            for (Way road : roads) {
                if (road.getNodesCount() < 2) continue;

                double[] cumDist = new double[road.getNodesCount()];
                cumDist[0] = 0.0;
                for (int i = 1; i < road.getNodesCount(); i++) {
                    cumDist[i] = cumDist[i - 1] + road.getNode(i - 1).getCoor().greatCircleDistance(road.getNode(i).getCoor());
                }
                double totalRoadLength = cumDist[cumDist.length - 1];

                double currentDist = rand.nextDouble() * (lastMakeRowHouses ? 5.0 : spacingMaxMeters);

                while (currentDist < totalRoadLength) {

                    if (lastMakeRowHouses) {
                        BuildingProfile activeProfile = activePool.get(rand.nextInt(activePool.size()));
                        double activeRatio = activeProfile.minRatio + rand.nextDouble() * (activeProfile.maxRatio - activeProfile.minRatio);

                        double rowHouseWidth = Math.sqrt(targetAreaMeters / activeRatio);

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

                            for (int u = 0; u < numUnits; u++) {
                                double unitDist = currentDist + (u * rowHouseWidth) + (rowHouseWidth / 2.0);
                                if (unitDist > totalRoadLength) break;

                                RoadPoint unitRoadPt = getPointAtDistance(road, unitDist, cumDist);
                                double unitAngle = unitRoadPt.angle;
                                double perpAngle = "Left".equals(side) ? unitAngle + Math.PI / 2.0 : unitAngle - Math.PI / 2.0;

                                double metersPerDegreeLat = 111000.0;
                                double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(unitRoadPt.pt.lat()));

                                double centerLatOffset = (sharedSetbackDist * Math.sin(perpAngle)) / metersPerDegreeLat;
                                double centerLonOffset = (sharedSetbackDist * Math.cos(perpAngle)) / metersPerDegreeLon;

                                LatLon buildingCenter = new LatLon(unitRoadPt.pt.lat() + centerLatOffset, unitRoadPt.pt.lon() + centerLonOffset);

                                List<Node> buildingNodes = createTransitionZoneNodes(buildingCenter, targetAreaMeters, unitAngle, activeProfile, true, rand);
                                if (buildingNodes.size() < 3) continue;

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
                        BuildingProfile activeProfile = activePool.get(rand.nextInt(activePool.size()));
                        double variance = 0.90 + (1.10 - 0.90) * rand.nextDouble();
                        double currentTargetArea = targetAreaMeters * variance;

                        double aspectRatio = activeProfile.minRatio + rand.nextDouble() * (activeProfile.maxRatio - activeProfile.minRatio);
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

                            List<Node> buildingNodes = createTransitionZoneNodes(buildingCenter, currentTargetArea, finalHeading, activeProfile, false, rand);
                            if (buildingNodes.size() < 3) continue;

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

                            if (lastGenerateDriveways) {
                                generateDriveway(dataSet, commands, roadPt.pt, buildingCenter, finalHeading, buildingWidth, setbackDist, rand);
                            }

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
            // GRID LOT GENERATION ENGINE
            for (Way boundary : boundaries) {
                LatLon center = getCentroid(boundary);

                double variance = lastMakeRowHouses ? 1.0 : (0.90 + (1.10 - 0.90) * rand.nextDouble());
                double currentTargetArea = targetAreaMeters * variance;

                double headingRotation;
                if (lastAlignToRoad && !roads.isEmpty()) {
                    headingRotation = calculateRoadAngle(center, roads);
                } else {
                    headingRotation = rand.nextDouble() * 2 * Math.PI;
                }

                if (maxRotationDegrees > 0 && !lastMakeRowHouses) {
                    double randomOffsetDegrees = (rand.nextDouble() * 2.0 - 1.0) * maxRotationDegrees;
                    headingRotation += Math.toRadians(randomOffsetDegrees);
                }

                BuildingProfile activeProfile = activePool.get(rand.nextInt(activePool.size()));
                List<Node> buildingNodes = createTransitionZoneNodes(center, currentTargetArea, headingRotation, activeProfile, lastMakeRowHouses, rand);

                if (buildingNodes.size() < 3) continue;

                double finalSetback = 0.0;
                if (lastAlignToRoad && !roads.isEmpty()) {
                    LatLon shiftVector = calculateControlledSetbackShift(center, roads, targetSetbackMinMeters, targetSetbackMaxMeters, rand);
                    for (Node node : buildingNodes) {
                        double newLat = node.getCoor().lat() + shiftVector.lat();
                        double newLon = node.getCoor().lon() + shiftVector.lon();
                        node.setCoor(new LatLon(newLat, newLon));
                    }

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

                if (!lastMakeRowHouses && lastGenerateDriveways && !roads.isEmpty()) {
                    LatLon nearestRoadPt = getNearestRoadPoint(center, roads);
                    double buildingWidthEstimated = Math.sqrt(currentTargetArea);
                    generateDriveway(dataSet, commands, nearestRoadPt, center, headingRotation, buildingWidthEstimated, finalSetback, rand);
                }

                if (!lastMakeRowHouses && lastGenerateSheds && !roads.isEmpty()) {
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

    // Interactive Dialog helper for Adding/Editing Building Forms
    private boolean showEditProfileDialog(BuildingProfile profile, JList<BuildingProfile> listToRepaint) {
        JTextField nameF = new JTextField(profile.name);
        JTextField minRF = new JTextField(String.valueOf(profile.minRatio));
        JTextField maxRF = new JTextField(String.valueOf(profile.maxRatio));
        JSlider freqSlider = new JSlider(0, 100, (int) (profile.insetFrequency * 100));
        JTextField minDistF = new JTextField(String.valueOf(profile.minInsetDistance)); // New Field

        boolean has90 = false, has45 = false, has225 = false;
        if (profile.allowedAngles != null) {
            for (double a : profile.allowedAngles) {
                if (Math.abs(a - Math.PI / 2.0) < 0.01) has90 = true;
                if (Math.abs(a - Math.PI / 4.0) < 0.01) has45 = true;
                if (Math.abs(a - Math.PI / 8.0) < 0.01) has225 = true;
            }
        }

        JCheckBox angle90 = new JCheckBox("90°", has90);
        JCheckBox angle45 = new JCheckBox("45°", has45);
        JCheckBox angle225 = new JCheckBox("22.5°", has225);

        JPanel dlgPanel = new JPanel(new GridLayout(7, 2, 5, 5));
        dlgPanel.add(new JLabel("Profile Name:"));
        dlgPanel.add(nameF);
        dlgPanel.add(new JLabel("Min Depth/Width Ratio:"));
        dlgPanel.add(minRF);
        dlgPanel.add(new JLabel("Max Depth/Width Ratio:"));
        dlgPanel.add(maxRF);
        dlgPanel.add(new JLabel("Inset Frequency (%):"));
        dlgPanel.add(freqSlider);
        dlgPanel.add(new JLabel("Min Inset/Outset Distance:")); // New Label
        dlgPanel.add(minDistF);
        dlgPanel.add(new JLabel("Allowed Angles:"));

        JPanel anglesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        anglesPanel.add(angle90);
        anglesPanel.add(angle45);
        anglesPanel.add(angle225);
        dlgPanel.add(anglesPanel);

        int res = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), dlgPanel,
                "Edit Building Profile Form", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (res == JOptionPane.OK_OPTION) {
            try {
                profile.name = nameF.getText();
                profile.minRatio = Double.parseDouble(minRF.getText());
                profile.maxRatio = Double.parseDouble(maxRF.getText());
                profile.insetFrequency = freqSlider.getValue() / 100.0;
                profile.minInsetDistance = Double.parseDouble(minDistF.getText()); // Read Value

                List<Double> angList = new ArrayList<>();
                if (angle90.isSelected()) angList.add(Math.PI / 2.0);
                if (angle45.isSelected()) angList.add(Math.PI / 4.0);
                if (angle225.isSelected()) angList.add(Math.PI / 8.0);
                if (angList.isEmpty()) angList.add(Math.PI / 2.0);

                profile.allowedAngles = new double[angList.size()];
                for (int i = 0; i < angList.size(); i++) profile.allowedAngles[i] = angList.get(i);

                if (listToRepaint != null) listToRepaint.repaint();
                return true;
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(), "Invalid numerical values entered.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        return false;
    }

    // PROCEDURAL ADD-ON GENERATOR ENGINE
    private void generateDriveway(DataSet dataSet, List<Command> commands, LatLon roadPt, LatLon houseCenter, double houseHeading, double houseWidth, double setbackMeters, Random rand) {
        if (setbackMeters <= 2.0) return;

        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(houseCenter.lat()));

        boolean parkOnLeft = rand.nextBoolean();
        double sideOffsetDist = (houseWidth / 2.0) + (1.5 + rand.nextDouble() * 2.0);
        if (parkOnLeft) sideOffsetDist = -sideOffsetDist;

        double perpOffsetAngle = houseHeading + Math.PI / 2.0;

        double offsetLat = (sideOffsetDist * Math.sin(perpOffsetAngle)) / metersPerDegreeLat;
        double offsetLon = (sideOffsetDist * Math.cos(perpOffsetAngle)) / metersPerDegreeLon;

        LatLon drivewayEnd = new LatLon(houseCenter.lat() + offsetLat, houseCenter.lon() + offsetLon);

        List<Node> drivewayNodes = new ArrayList<>();

        Node startNode = new Node(roadPt);
        commands.add(new AddCommand(dataSet, startNode));
        drivewayNodes.add(startNode);

        double lengthFeet = setbackMeters / 0.3048;

        if (lengthFeet > 70.0) {
            int numInterNodes = 4 + rand.nextInt(4);
            double curvatureStrength = 3.0 + rand.nextDouble() * 6.0;
            boolean curveDirection = rand.nextBoolean();

            for (int i = 1; i < numInterNodes; i++) {
                double t = (double) i / numInterNodes;

                double interpLat = roadPt.lat() + t * (drivewayEnd.lat() - roadPt.lat());
                double interpLon = roadPt.lon() + t * (drivewayEnd.lon() - roadPt.lon());

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
            if (rand.nextBoolean()) {
                double interpLat = roadPt.lat() + 0.5 * (drivewayEnd.lat() - roadPt.lat());
                double interpLon = roadPt.lon() + 0.5 * (drivewayEnd.lon() - roadPt.lon());

                double wiggleDist = (rand.nextDouble() * 2.0 - 1.0);
                double wiggleAngle = perpOffsetAngle;

                double wiggleLat = (wiggleDist * Math.sin(wiggleAngle)) / metersPerDegreeLat;
                double wiggleLon = (wiggleDist * Math.cos(wiggleAngle)) / metersPerDegreeLon;

                Node elbowNode = new Node(new LatLon(interpLat + wiggleLat, interpLon + wiggleLon));
                commands.add(new AddCommand(dataSet, elbowNode));
                drivewayNodes.add(elbowNode);
            }
        }

        Node endNode = new Node(drivewayEnd);
        commands.add(new AddCommand(dataSet, endNode));
        drivewayNodes.add(endNode);

        Way drivewayWay = new Way();
        for (Node n : drivewayNodes) {
            drivewayWay.addNode(n);
        }

        drivewayWay.put("highway", "service");
        drivewayWay.put("lanes", "1");
        drivewayWay.put("service", "driveway");
        drivewayWay.put("width", "4");

        commands.add(new AddCommand(dataSet, drivewayWay));
    }

    private void generateBackyardShed(DataSet dataSet, List<Command> commands, LatLon houseCenter, double houseHeading, double setbackPerpAngle, Random rand) {
        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(houseCenter.lat()));

        double yardDepthOffset = 10.0 + rand.nextDouble() * 12.0;
        double sideYardWiggle = (rand.nextDouble() * 2.0 - 1.0) * 4.0;

        double backyardLat = (yardDepthOffset * Math.sin(setbackPerpAngle) + sideYardWiggle * Math.sin(setbackPerpAngle + Math.PI / 2.0)) / metersPerDegreeLat;
        double backyardLon = (yardDepthOffset * Math.cos(setbackPerpAngle) + sideYardWiggle * Math.cos(setbackPerpAngle + Math.PI / 2.0)) / metersPerDegreeLon;

        LatLon shedCenter = new LatLon(houseCenter.lat() + backyardLat, houseCenter.lon() + backyardLon);

        double shedWidth = (8.0 + rand.nextDouble() * 4.0) * 0.3048;
        double shedHeight = (10.0 + rand.nextDouble() * 4.0) * 0.3048;

        double halfW = shedWidth / 2.0;
        double halfH = shedHeight / 2.0;

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
        shedWay.addNode(shedNodes.get(0));
        shedWay.put("building", "shed");

        commands.add(new AddCommand(dataSet, shedWay));
    }

    // COLLISION PREVENTATIVE MAPPING METHODS
    private boolean intersectsExistingObjects(List<Node> newBuildingNodes, DataSet dataSet, List<List<LatLon>> newlyCreatedPolygons) {
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
        for (List<LatLon> poly : newlyCreatedPolygons) {
            if (intersectsWay(newBuildingNodes, poly, true)) {
                return true;
            }
        }
        return false;
    }

    private boolean intersectsWay(List<Node> polyNodes, List<LatLon> wayCoords, boolean isClosed) {
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

    // ADDITIONAL MATH & DISTANCE HELPERS
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

    // DYNAMIC GEOMETRY INSET GENERATION ENGINE
    private List<Node> createTransitionZoneNodes(LatLon center, double targetArea, double headingRotation, BuildingProfile profile, boolean isRowHouse, Random rand) {
        double metersPerDegreeLat = 111000.0;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(center.lat()));

        // Use selected profile unit conversion factor (feet -> meters vs meters -> meters)
        double unitConversion = "Sq Feet".equals(lastUnitSelection) ? 0.3048 : 1.0;
        double minSegmentLengthMeters = profile.minInsetDistance * unitConversion;

        double aspectRatio = profile.minRatio + rand.nextDouble() * (profile.maxRatio - profile.minRatio);
        double baseWidthMeters = Math.sqrt(targetArea / aspectRatio);
        double baseHeightMeters = targetArea / baseWidthMeters;

        double halfW = baseWidthMeters / 2.0;
        double halfH = baseHeightMeters / 2.0;

        List<Point2D> perimeter = new ArrayList<>();
        perimeter.add(new Point2D(-halfW, -halfH));
        perimeter.add(new Point2D(halfW, -halfH));
        perimeter.add(new Point2D(halfW, halfH));
        perimeter.add(new Point2D(-halfW, halfH));

        if (rand.nextDouble() < profile.insetFrequency) {
            int totalCuts = rand.nextInt(3) + 1;

            for (int c = 0; c < totalCuts; c++) {
                if (perimeter.size() < 4) break;

                int edgeIndex = rand.nextInt(perimeter.size());

                if (isRowHouse && (edgeIndex != 0 && edgeIndex != 2)) {
                    continue;
                }

                Point2D p1 = perimeter.get(edgeIndex);
                Point2D p2 = perimeter.get((edgeIndex + 1) % perimeter.size());

                double edgeDx = p2.x - p1.x;
                double edgeDy = p2.y - p1.y;
                double edgeLength = Math.sqrt(edgeDx * edgeDx + edgeDy * edgeDy);

                if (edgeLength < (minSegmentLengthMeters * 3.0)) continue;

                double cutDepthMeters = (2.0 + rand.nextDouble() * 6.0) * 0.3048;
                double maxPossibleCutLength = edgeLength - (minSegmentLengthMeters * 2.0);
                if (maxPossibleCutLength <= minSegmentLengthMeters) continue;

                double cutLengthMeters = minSegmentLengthMeters + rand.nextDouble() * (maxPossibleCutLength - minSegmentLengthMeters);

                double availableBufferSpace = edgeLength - cutLengthMeters - (minSegmentLengthMeters * 2.0);
                double startOffset = minSegmentLengthMeters + (availableBufferSpace > 0 ? rand.nextDouble() * availableBufferSpace : 0);

                double uX = edgeDx / edgeLength;
                double uY = edgeDy / edgeLength;

                Point2D cutStart = new Point2D(p1.x + uX * startOffset, p1.y + uY * startOffset);
                Point2D cutEnd = new Point2D(cutStart.x + uX * cutLengthMeters, cutStart.y + uY * cutLengthMeters);

                double chosenAngle = profile.allowedAngles[rand.nextInt(profile.allowedAngles.length)];

                double normX = -uY;
                double normY = uX;

                double cutAngleOffsetDx = normX * Math.sin(chosenAngle) + uX * Math.cos(chosenAngle);
                double cutAngleOffsetDy = normY * Math.sin(chosenAngle) + uY * Math.cos(chosenAngle);

                boolean makeCutOut = rand.nextBoolean();
                double directionFactor = makeCutOut ? 1.0 : -1.0;

                Point2D cutStepIn = new Point2D(cutStart.x + cutAngleOffsetDx * cutDepthMeters * directionFactor,
                                                cutStart.y + cutAngleOffsetDy * cutDepthMeters * directionFactor);
                Point2D cutStepOut = new Point2D(cutEnd.x + cutAngleOffsetDx * cutDepthMeters * directionFactor,
                                                 cutEnd.y + cutAngleOffsetDy * cutDepthMeters * directionFactor);

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
