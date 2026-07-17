package org.openstreetmap.josm.plugins.virestrom;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.swing.*;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.data.UndoRedoHandler;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.polygonize.Polygonizer;

public class SplitAction extends JosmAction {

    private final GeometryFactory gf = new GeometryFactory();
    private final Random rand = new Random();

    private static double lastAvgFieldSize = 80.0;
    private static double lastAvgResSize = 2.0;
    private static double lastResDensity = 2.0;
    private static int lastVariation = 30;

    public SplitAction() {
        super("Split Indiana Block", "split_icon", "Splits a landuse polygon into rural lots",
                Shortcut.registerShortcut("tools:indianasplitter", "Split Indiana Block", KeyEvent.VK_I, Shortcut.ALT_CTRL), true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) return;

        Collection<Way> selectedWays = dataSet.getSelectedWays();
        if (selectedWays.isEmpty()) return;

        Way selectedMacroWay = null;
        List<Way> selectedObstacles = new ArrayList<>();

        for (Way way : selectedWays) {
            if (!way.isClosed()) continue;
            String naturalTag = way.get("natural");

            if ("wood".equals(naturalTag) || "grassland".equals(naturalTag)) {
                selectedObstacles.add(way);
            } else if (selectedMacroWay == null) {
                selectedMacroWay = way;
            }
        }

        if (selectedMacroWay == null && selectedWays.size() == 1) {
            Way loneWay = selectedWays.iterator().next();
            if (loneWay.isClosed()) {
                selectedMacroWay = loneWay;
            }
        }

        if (selectedMacroWay == null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Please select a main outer boundary way (and optionally any overlapping natural=wood/grassland ways).",
                    "Split Indiana Block", JOptionPane.PLAIN_MESSAGE);
            return;
        }

        List<Coordinate> jtsCoords = new ArrayList<>();
        double totalLat = 0;
        for (Node node : selectedMacroWay.getNodes()) {
            jtsCoords.add(new Coordinate(node.getCoor().getX(), node.getCoor().getY()));
            totalLat += node.getCoor().getY();
        }

        Polygon originalPolygon = gf.createPolygon(jtsCoords.toArray(new Coordinate[0]));
        Geometry originalBoundary = originalPolygon.getBoundary();

        double avgLatRad = Math.toRadians(totalLat / selectedMacroWay.getNodes().size());
        double metersPerDegLat = 111132.0;
        double metersPerDegLon = 111412.0 * Math.cos(avgLatRad);
        double sqDegToSqMeters = metersPerDegLat * metersPerDegLon;
        double sqMetersToAcres = 0.000247105;

        double totalAcres = originalPolygon.getArea() * sqDegToSqMeters * sqMetersToAcres;
        double totalSqMiles = totalAcres / 640.0;

        JPanel panel = new JPanel(new GridLayout(4, 2, 10, 10));
        JSpinner avgFieldSizeSpinner = new JSpinner(new SpinnerNumberModel(lastAvgFieldSize, 5.0, 1000.0, 10.0));
        JSpinner avgResSizeSpinner = new JSpinner(new SpinnerNumberModel(lastAvgResSize, 0.5, 20.0, 0.5));
        JSpinner resDensitySpinner = new JSpinner(new SpinnerNumberModel(lastResDensity, 0.0, 20.0, 0.5));
        JSlider variationSlider = new JSlider(0, 100, lastVariation);

        panel.add(new JLabel("Avg Crop Field Size (Acres):"));
        panel.add(avgFieldSizeSpinner);
        panel.add(new JLabel("Avg Homestead Size (Acres):"));
        panel.add(avgResSizeSpinner);
        panel.add(new JLabel("Homestead Density (per Sq Mile):"));
        panel.add(resDensitySpinner);
        panel.add(new JLabel("Layout Variation (% Off-Center):"));
        panel.add(variationSlider);

        String title = String.format("Indiana Generator (Detected: %.1f Acres / %.2f SqMi)", totalAcres, totalSqMiles);
        int result = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), panel,
                title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        lastAvgFieldSize = (double) avgFieldSizeSpinner.getValue();
        lastAvgResSize = (double) avgResSizeSpinner.getValue();
        lastResDensity = (double) resDensitySpinner.getValue();
        lastVariation = variationSlider.getValue();

        double varianceModifier = lastVariation / 100.0;
        Geometry arableFarmlandSpace = originalPolygon;
        List<Geometry> naturalBoundariesForSafety = new ArrayList<>();
        double protectiveSetbackBufferValue = 0.00004;

        for (Way obsWay : selectedObstacles) {
            List<Coordinate> obstacleCoords = new ArrayList<>();
            for (Node n : obsWay.getNodes()) {
                obstacleCoords.add(new Coordinate(n.getCoor().getX(), n.getCoor().getY()));
            }
            if (obstacleCoords.size() < 4) continue;
            Polygon obstaclePoly = gf.createPolygon(obstacleCoords.toArray(new Coordinate[0]));

            if (originalPolygon.intersects(obstaclePoly)) {
                try {
                    Geometry bufferedObstacle = obstaclePoly.buffer(protectiveSetbackBufferValue);
                    arableFarmlandSpace = arableFarmlandSpace.difference(bufferedObstacle);
                    naturalBoundariesForSafety.add(obstaclePoly.getBoundary());
                } catch (Exception ex) {
                    // Fail-safe handling for geometric precision errors
                }
            }
        }

        List<Polygon> initialBases = unpackToPolygons(arableFarmlandSpace);
        double currentUsableAcres = arableFarmlandSpace.getArea() * sqDegToSqMeters * sqMetersToAcres;
        int targetFields = (int) Math.max(1, Math.round(currentUsableAcres / lastAvgFieldSize));
        int targetHomesteads = (int) Math.round(totalSqMiles * lastResDensity);

        List<Polygon> cropFields = new ArrayList<>(initialBases);
        while (cropFields.size() < targetFields) {
            int largestIdx = 0;
            double maxArea = 0;
            for (int i = 0; i < cropFields.size(); i++) {
                double area = cropFields.get(i).getArea();
                if (area > maxArea) {
                    maxArea = area;
                    largestIdx = i;
                }
            }

            Polygon parent = cropFields.remove(largestIdx);
            List<Polygon> children = splitSinglePolygon(parent, varianceModifier);

            if (children.size() >= 2) {
                cropFields.addAll(children);
            } else {
                cropFields.add(parent);
                break;
            }
        }

        List<Polygon> residentialLots = new ArrayList<>();
        double baseResJTSArea = lastAvgResSize / (sqDegToSqMeters * sqMetersToAcres);

        for (int h = 0; h < targetHomesteads; h++) {
            boolean success = false;
            double sizeMultiplier = 0.5 + (rand.nextDouble() * 1.5);
            double individualizedResTargetJTSArea = baseResJTSArea * sizeMultiplier;

            for (int attempt = 0; attempt < cropFields.size(); attempt++) {
                Polygon targetField = cropFields.get(attempt);
                Geometry sharedFrontage = targetField.getBoundary().intersection(originalBoundary);
                if (sharedFrontage.isEmpty() || sharedFrontage.getLength() == 0) {
                    continue;
                }

                List<Polygon> carvedResult = carveRoadFrontageHomestead(targetField, sharedFrontage, individualizedResTargetJTSArea);
                if (carvedResult.size() == 2) {
                    Polygon potentialRes = (carvedResult.get(0).getArea() < carvedResult.get(1).getArea()) ? carvedResult.get(0) : carvedResult.get(1);
                    Polygon potentialFarm = (carvedResult.get(0).getArea() < carvedResult.get(1).getArea()) ? carvedResult.get(1) : carvedResult.get(0);
                    cropFields.remove(attempt);
                    residentialLots.add(potentialRes);
                    cropFields.add(potentialFarm);
                    success = true;
                    break;
                }
            }
            if (!success) break;
        }

        List<org.openstreetmap.josm.command.Command> commands = new ArrayList<>();
        Map<String, Node> nodeCache = new HashMap<>();
        List<Geometry> protectionBoundaries = new ArrayList<>();
        for (Polygon rLot : residentialLots) {
            protectionBoundaries.add(rLot.getBoundary());
        }
        protectionBoundaries.addAll(naturalBoundariesForSafety);

        for (Polygon p : cropFields) {
            Geometry organicFarmland = generateOrganicFarmland(p, originalBoundary, protectionBoundaries);
            List<Polygon> finalPolys = unpackToPolygons(organicFarmland);
            for (Polygon finalPoly : finalPolys) {
                commands.addAll(createStitchedJOSMWay(finalPoly, dataSet, "landuse", "farmland", nodeCache));
            }
        }

        for (Polygon p : residentialLots) {
            commands.addAll(createStitchedJOSMWay(p, dataSet, "landuse", "residential", nodeCache));
        }

        List<OsmPrimitive> primitivesToDelete = new ArrayList<>();
        primitivesToDelete.add(selectedMacroWay);
        for (Node oldNode : selectedMacroWay.getNodes()) {
            boolean belongsToObstacle = false;
            for (Way obs : selectedObstacles) {
                if (obs.getNodes().contains(oldNode)) {
                    belongsToObstacle = true;
                    break;
                }
            }
            if (!belongsToObstacle && oldNode.getParentWays().size() <= 1) {
                if (!primitivesToDelete.contains(oldNode)) {
                    primitivesToDelete.add(oldNode);
                }
            }
        }

        commands.add(new DeleteCommand(primitivesToDelete));
        UndoRedoHandler.getInstance().add(new SequenceCommand("Indiana Multi-Selection Parcel Splitter", commands));
    }

    private List<Polygon> splitSinglePolygon(Polygon poly, double varianceModifier) {
        List<Polygon> output = new ArrayList<>();
        org.locationtech.jts.geom.Envelope env = poly.getEnvelopeInternal();

        double shiftPercent = (rand.nextDouble() - 0.5) * varianceModifier;
        LineString cutLine;

        if (env.getWidth() > env.getHeight()) {
            double splitX = env.getMinX() + (env.getWidth() * (0.5 + shiftPercent));
            Coordinate[] cutCoords = { new Coordinate(splitX, env.getMinY() - 0.1), new Coordinate(splitX, env.getMaxY() + 0.1) };
            cutLine = gf.createLineString(cutCoords);
        } else {
            double splitY = env.getMinY() + (env.getHeight() * (0.5 + shiftPercent));
            Coordinate[] cutCoords = { new Coordinate(env.getMinX() - 0.1, splitY), new Coordinate(env.getMaxX() + 0.1, splitY) };
            cutLine = gf.createLineString(cutCoords);
        }

        Geometry boundary = poly.getBoundary();
        Geometry union = boundary.union(cutLine);
        Polygonizer polygonizer = new Polygonizer();
        polygonizer.add(union);

        for (Object obj : polygonizer.getPolygons()) {
            output.add((Polygon) obj);
        }
        return output;
    }

    private List<Polygon> carveRoadFrontageHomestead(Polygon field, Geometry sharedFrontage, double targetJTSArea) {
        List<Polygon> output = new ArrayList<>();
        Coordinate[] frontageCoords = sharedFrontage.getCoordinates();
        if (frontageCoords.length < 2) return output;

        Coordinate roadPt1 = frontageCoords[0];
        Coordinate roadPt2 = frontageCoords[1];
        double dx = roadPt2.x - roadPt1.x;
        double dy = roadPt2.y - roadPt1.y;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return output;

        double ux = dx / len;
        double uy = dy / len;
        double px = -uy;
        double py = ux;

        Coordinate testInterior = new Coordinate(roadPt1.x + px * 0.0001, roadPt1.y + py * 0.0001);
        if (!field.contains(gf.createPoint(testInterior))) {
            px = -px;
            py = -py;
        }

        double aspectRatio = 1.2 + (rand.nextDouble() * 0.8);
        double width = Math.sqrt(targetJTSArea * aspectRatio);
        double depth = targetJTSArea / width;

        if (len < width) {
            width = len * 0.6;
            depth = targetJTSArea / width;
        }

        double startDist = (len - width) * rand.nextDouble();
        if (startDist < 0) startDist = len * 0.1;

        Coordinate p0 = new Coordinate(roadPt1.x + ux * startDist, roadPt1.y + uy * startDist);
        Coordinate p1 = new Coordinate(p0.x + ux * width, p0.y + uy * width);
        Coordinate p2 = new Coordinate(p1.x + px * depth, p1.y + py * depth);
        Coordinate p3 = new Coordinate(p0.x + px * depth, p0.y + py * depth);

        Coordinate[] cutPoints = { p0, p3, p2, p1 };
        LineString cutPath = gf.createLineString(cutPoints);
        Geometry boundary = field.getBoundary();
        Geometry union = boundary.union(cutPath);
        Polygonizer polygonizer = new Polygonizer();
        polygonizer.add(union);

        for (Object obj : polygonizer.getPolygons()) {
            output.add((Polygon) obj);
        }
        return output;
    }

    private List<Polygon> unpackToPolygons(Geometry geom) {
        List<Polygon> polys = new ArrayList<>();
        if (geom == null || geom.isEmpty()) return polys;
        if (geom instanceof Polygon) {
            polys.add((Polygon) geom);
        } else {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                Geometry child = geom.getGeometryN(i);
                if (child instanceof Polygon) {
                    polys.add((Polygon) child);
                }
            }
        }
        return polys;
    }

    private Geometry generateOrganicFarmland(Polygon field, Geometry originalBoundary, List<Geometry> protectionBoundaries) {
        Coordinate[] origCoords = field.getExteriorRing().getCoordinates();
        List<Coordinate> highResCoords = new ArrayList<>();

        for (int i = 0; i < origCoords.length - 1; i++) {
            Coordinate c1 = origCoords[i];
            Coordinate c2 = origCoords[i + 1];
            Coordinate[] segmentPair = { c1, c2 };
            LineString currentSegment = gf.createLineString(segmentPair);

            boolean touchesProtectedZone = false;
            for (Geometry protectionBound : protectionBoundaries) {
                if (currentSegment.within(protectionBound.buffer(0.0001))) {
                    touchesProtectedZone = true;
                    break;
                }
            }

            if (touchesProtectedZone) {
                highResCoords.add(c1);
                continue;
            }

            double dx = c2.x - c1.x;
            double dy = c2.y - c1.y;
            double segmentLength = Math.sqrt(dx * dx + dy * dy);

            int intervals = (int) Math.max(12, Math.round(segmentLength * 4000.0));
            if (intervals > 25) intervals = 25;

            for (int j = 0; j < intervals; j++) {
                double fraction = (double) j / intervals;
                double interimX = c1.x + dx * fraction;
                double interimY = c1.y + dy * fraction;
                Coordinate samplePt = new Coordinate(interimX, interimY);

                double insetDistance = 0.00004;
                if (originalBoundary.distance(gf.createPoint(samplePt)) < 0.0001) {
                    insetDistance = 0.00012 + (Math.sin(fraction * Math.PI) * 0.00006);
                }

                highResCoords.add(samplePt);
            }
        }
        highResCoords.add(highResCoords.get(0));

        Polygon expandedPoly = gf.createPolygon(highResCoords.toArray(new Coordinate[0]));
        Coordinate[] expandedCoords = expandedPoly.getExteriorRing().getCoordinates();

        List<Coordinate> finalOrganicCoords = new ArrayList<>();
        Coordinate centroid = field.getCentroid().getCoordinate();

        for (int i = 0; i < expandedCoords.length - 1; i++) {
            Coordinate curr = expandedCoords[i];
            org.locationtech.jts.geom.Point currPt = gf.createPoint(curr);

            boolean nearProtectedZone = false;
            for (Geometry protectionBound : protectionBoundaries) {
                if (currPt.distance(protectionBound) < 0.00012) {
                    nearProtectedZone = true;
                    break;
                }
            }

            if (nearProtectedZone) {
                finalOrganicCoords.add(curr);
                continue;
            }

            double toCenterX = centroid.x - curr.x;
            double toCenterY = centroid.y - curr.y;
            double distToCenter = Math.sqrt(toCenterX * toCenterX + toCenterY * toCenterY);
            double normX = toCenterX / distToCenter;
            double normY = toCenterY / distToCenter;

            double waveFrequency = 12.0;
            double waveAmplitude = 0.00003;
            double distortionShift = Math.sin((double) i / expandedCoords.length * Math.PI * waveFrequency) * waveAmplitude;

            double finalX = curr.x + (normX * (0.00006 + distortionShift));
            double finalY = curr.y + (normY * (0.00006 + distortionShift));
            finalOrganicCoords.add(new Coordinate(finalX, finalY));
        }

        List<Coordinate> smoothedCoords = new ArrayList<>();
        int size = finalOrganicCoords.size();
        for (int i = 0; i < size; i++) {
            Coordinate curr = finalOrganicCoords.get(i);
            org.locationtech.jts.geom.Point currPt = gf.createPoint(curr);

            boolean nearProtectedZone = false;
            for (Geometry protectionBound : protectionBoundaries) {
                if (currPt.distance(protectionBound) < 0.00012) {
                    nearProtectedZone = true;
                    break;
                }
            }

            if (nearProtectedZone) {
                smoothedCoords.add(curr);
            } else {
                Coordinate prev = finalOrganicCoords.get((i - 1 + size) % size);
                Coordinate next = finalOrganicCoords.get((i + 1) % size);
                double avgX = (prev.x * 0.25) + (curr.x * 0.50) + (next.x * 0.25);
                double avgY = (prev.y * 0.25) + (curr.y * 0.50) + (next.y * 0.25);
                smoothedCoords.add(new Coordinate(avgX, avgY));
            }
        }
        smoothedCoords.add(smoothedCoords.get(0));
        return gf.createPolygon(smoothedCoords.toArray(new Coordinate[0]));
    }

    private List<org.openstreetmap.josm.command.Command> createStitchedJOSMWay(
            Polygon p, DataSet dataSet, String tagKey, String tagValue, Map<String, Node> nodeCache) {

        List<org.openstreetmap.josm.command.Command> cmds = new ArrayList<>();
        List<Node> newNodes = new ArrayList<>();
        Coordinate[] coords = p.getExteriorRing().getCoordinates();

        for (int i = 0; i < coords.length - 1; i++) {
            String coordKey = String.format("%.7f_%.7f", coords[i].x, coords[i].y);
            Node n;
            if (nodeCache.containsKey(coordKey)) {
                n = nodeCache.get(coordKey);
            } else {
                n = new Node(new LatLon(coords[i].y, coords[i].x));
                cmds.add(new AddCommand(dataSet, n));
                nodeCache.put(coordKey, n);
            }
            newNodes.add(n);
        }
        newNodes.add(newNodes.get(0));

        Way newWay = new Way();
        newWay.setNodes(newNodes);
        newWay.put(tagKey, tagValue);
        cmds.add(new AddCommand(dataSet, newWay));

        return cmds;
    }

    @Override
    protected void updateEnabledState() {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        setEnabled(dataSet != null && !dataSet.getSelectedWays().isEmpty());
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        setEnabled(dataSet != null && !dataSet.getSelectedWays().isEmpty());
    }
}
