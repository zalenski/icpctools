package org.icpc.tools.presentation.admin.internal;

import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MultiScene {
    public JPanel createScenesPanel(MultiView view) {
        JPanel scenesPanel = new JPanel(new BorderLayout());

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        scenesPanel.add(mainSplit, BorderLayout.CENTER);

        // Left Panel, listing scenes and buttons to handle them
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BorderLayout());
        leftPanel.add(sceneList, BorderLayout.CENTER);
        sceneList.setListData(scenes.stream().map(s -> s.name).toArray(String[]::new));

        // Right
        // Right Panel
        JSplitPane rightPanel = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightPanel.setTopComponent(presetsList);

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(rightPanel);

        return scenesPanel;
    }

    JList<String> sceneList = new JList<>();
    JList<String> presetsList = new JList<>();

    List<Scene> scenes = Multi48Astana.makeSomeScenes();

    /**
     * Represents a scene, which is a set of presentations or composite presentations, and which clients
     * to show them on.
     */
    public static class Scene {
        public Scene(String name, Map<String, String> presetMap) {
            this.name = name;
            for (Map.Entry<String, String> entry : presetMap.entrySet()) {
                this.presetMap.put(entry.getKey(), new Preset(entry.getValue()));
            }
        }
        String name;
        // map clients to presets
        Map<String, Preset> presetMap = new TreeMap<>();

        // TODO: triggers and enabling/disabling them
        boolean triggerEnabled;
        int triggerTime;
    }

    public static class Preset {
        public Preset(String preset) {
            String message = null;
            String[] parts = preset.split("/", 2);
            if (parts.length > 1) {
                preset = parts[0];
                message = parts[1];
            }
            this.preset = preset;
            this.message = message;
        }
        String preset; // presentation or composite name
        String message;
    }
}
