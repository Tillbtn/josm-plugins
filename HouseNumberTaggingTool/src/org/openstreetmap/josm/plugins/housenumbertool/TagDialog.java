// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.housenumbertool;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.*;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.tagging.ac.AutoCompletionItem;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.tagging.ac.AutoCompComboBox;
import org.openstreetmap.josm.gui.tagging.ac.AutoCompletionManager;
import org.openstreetmap.josm.gui.widgets.JosmComboBox;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * The dialog to show users the tags that will be applied to an object
 *
 * @author Oliver Raupach 09.01.2012
 * @author Victor Kropp 10.03.2012
 */
public class TagDialog extends ExtendedDialog {
    private static final long serialVersionUID = -4781477945608720136L;
    private static final String APPLY_CHANGES = tr("Apply Changes");
    private static final String TAG_STREET_OR_PLACE = tr("Use tag ''addr:street'' or ''addr:place''");

    private static final String TAG_BUILDING = "building";
    private static final String TAG_HOUSE = "house";
    private static final String TAG_ENTRANCE = "entrance";
    private static final String TAG_ADDR_COUNTRY = "addr:country";
    private static final String TAG_ADDR_CITY = "addr:city";
    private static final String TAG_ADDR_POSTCODE = "addr:postcode";
    private static final String TAG_ADDR_HOUSENUMBER = "addr:housenumber";
    private static final String TAG_ADDR_STREET = "addr:street";
    private static final String TAG_ADDR_PLACE = "addr:place";
    private static final String TAG_ADDR_SUBURB = "addr:suburb";
    private static final String TAG_INCREMENT_NUMBER = tr("increment number");
    private static final String TAG_INCREMENT_LETTER = tr("increment letter");

    private static final String[] BUILDING_STRINGS = {
            "yes", "apartments", "house", "semidetached_house", "residential", "detached"};

    private static final String[] ENTRANCE_STRINGS = {
            "yes", "main", "shop", "home", "exit"};

    private static final int FPS_MIN = -10;
    private static final int FPS_MAX = 10;

    private final File pluginDir;
    private AutoCompletionManager acm;
    private final OsmPrimitive selection;

    private static final String TEMPLATE_DATA = "/template.data";

    private AutoCompComboBox<AutoCompletionItem> country;
    private AutoCompComboBox<AutoCompletionItem> suburb;
    private AutoCompComboBox<AutoCompletionItem> city;
    private AutoCompComboBox<AutoCompletionItem> postcode;
    private AutoCompComboBox<AutoCompletionItem> street;
    private JTextField housenumber;
    private JCheckBox buildingEnabled;
    private JCheckBox houseEnabled;
    private JCheckBox entranceEnabled;
    private JCheckBox countryEnabled;
    private JCheckBox cityEnabled;
    private JCheckBox suburbEnabled;
    private JCheckBox zipEnabled;
    private JCheckBox streetEnabled;
    private JCheckBox housenumberEnabled;
    private JSlider housenumberChangeSequence;
    private JComboBox<String> building;
    private JTextField house;
    private JComboBox<String> entrance;
    private JRadioButton streetRadio;
    private JRadioButton placeRadio;
    private JRadioButton incrementNumRadio;
    private JRadioButton incrementLetterRadio;
    private final Boolean forceLetterIncrement;

    /**
     * Constructs a new {@code TagDialog}.
     * @param pluginDir plugin directory
     * @param selection selected primitive
     */
    public TagDialog(File pluginDir, OsmPrimitive selection) {
        this(pluginDir, selection, null);
    }

    /**
     * Constructs a new {@code TagDialog}.
     * 
     * @param pluginDir            plugin directory
     * @param selection            selected primitive
     * @param forceLetterIncrement if true, force letter increment mode; if false,
     *                             force number increment mode; if null, use last
     *                             saved setting
     */
    public TagDialog(File pluginDir, OsmPrimitive selection, Boolean forceLetterIncrement) {
        super(MainApplication.getMainFrame(), tr("House Number Editor"), new String[] { tr("OK"), tr("Cancel") }, true);
        this.pluginDir = pluginDir;
        this.selection = selection;
        this.forceLetterIncrement = forceLetterIncrement;

        JPanel editPanel = createContentPane();

        setPreferredSize(new Dimension(900, 600)); // changed from 900;500
        setMinimumSize(new Dimension(900, 600));

        setContent(editPanel);
        setButtonIcons("ok", "cancel");
        setDefaultButton(1);
        setupDialog();
        getRootPane().setDefaultButton(defaultButton);

        // middle of the screen
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            housenumber.requestFocus();
            housenumber.selectAll();
        });

        // add selection listener for building / entrance
        addSelectionListener();
        updateCheckboxStates();
    }

    private JPanel createContentPane() {
        acm = AutoCompletionManager.of(selection.getDataSet());

        Dto dto = loadDto();

        JPanel editPanel = new JPanel(new GridBagLayout());

        JLabel labelNewValues = new JLabel();
        Font newLabelFont = new Font(labelNewValues.getFont().getName(), Font.BOLD, labelNewValues.getFont().getSize());
        labelNewValues.setFont(newLabelFont);
        labelNewValues.setText(tr("New values:"));
        editPanel.add(labelNewValues, GBC.std().grid(3, 0).insets(0, 5, 10, 5));

        JLabel labelExistingValues = new JLabel();
        labelExistingValues.setFont(newLabelFont);
        labelExistingValues.setText(tr("Existing values:"));
        editPanel.add(labelExistingValues, GBC.std().span(3).weight(0, 0).grid(5, 0).insets(0, 5, 10, 5));

        JButton getAllButton = new JButton("<<");
        getAllButton.setPreferredSize(new Dimension(60, 24));
        getAllButton.setToolTipText(tr("Accept all existing values"));
        getAllButton.addActionListener(actionEvent -> acceptAllExistingValues());
        editPanel.add(getAllButton, GBC.eol().grid(7, 0).anchor(GridBagConstraints.EAST).insets(0, 5, 10, 5));

        GBC columnOne = GBC.std().span(3).insets(5, 5, 0, 5);
        GBC columnTwo = GBC.std().span(1).weight(1, 0).fill(GBC.HORIZONTAL).insets(5, 5, 0, 5);
        GBC columnThree = GBC.std().insets(5, 5, 0, 5);
        GBC columnFour = GBC.eol().weight(1, 0).fill(GBC.HORIZONTAL).insets(5, 5, 0, 5);

        // building
        buildingEnabled = generateCheckbox(TAG_BUILDING, dto.isSaveBuilding());
        editPanel.add(buildingEnabled, columnOne);

        Arrays.sort(BUILDING_STRINGS);
        building = new JosmComboBox<>(BUILDING_STRINGS);
        building.setSelectedItem(dto.getBuilding());
        building.setMaximumRowCount(50);
        editPanel.add(building, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> building.setSelectedItem(selection.get(TAG_BUILDING))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_BUILDING)), columnFour);

        // house
        houseEnabled = generateCheckbox(TAG_HOUSE, dto.isSaveHouse());
        editPanel.add(houseEnabled, columnOne);

        house = generateTextField(dto.getHouse());
        house.setEditable(true);
        editPanel.add(house, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> house.setText(selection.get(TAG_HOUSE))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_HOUSE)), columnFour);

        // entrance
        entranceEnabled = generateCheckbox(TAG_ENTRANCE, dto.isSaveEntrance());
        editPanel.add(entranceEnabled, columnOne);

        entrance = new JosmComboBox<>(ENTRANCE_STRINGS);
        entrance.setSelectedItem(dto.getEntrance());
        editPanel.add(entrance, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> entrance.setSelectedItem(selection.get(TAG_ENTRANCE))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_ENTRANCE)), columnFour);

        // country
        countryEnabled = generateCheckbox(TAG_ADDR_COUNTRY, dto.isSaveCountry());
        editPanel.add(countryEnabled, columnOne);

        country = generateAutoCompTextField(acm.getTagValues(TAG_ADDR_COUNTRY), dto.getCountry());
        editPanel.add(country, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> country.setSelectedItem(selection.get(TAG_ADDR_COUNTRY))),
                columnThree);
        editPanel.add(generateTextField(selection.get(TAG_ADDR_COUNTRY)), columnFour);

        // suburb
        suburbEnabled = generateCheckbox(TAG_ADDR_SUBURB, dto.isSaveSuburb());
        editPanel.add(suburbEnabled, columnOne);

        suburb = generateAutoCompTextField(acm.getTagValues(TAG_ADDR_SUBURB), dto.getSuburb());
        editPanel.add(suburb, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> suburb.setSelectedItem(selection.get(TAG_ADDR_SUBURB))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_ADDR_SUBURB)), columnFour);

        // city
        cityEnabled = generateCheckbox(TAG_ADDR_CITY, dto.isSaveCity());
        editPanel.add(cityEnabled, columnOne);

        city = generateAutoCompTextField(acm.getTagValues(TAG_ADDR_CITY), dto.getCity());
        editPanel.add(city, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> city.setSelectedItem(selection.get(TAG_ADDR_CITY))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_ADDR_CITY)), columnFour);

        // postcode
        zipEnabled = generateCheckbox(TAG_ADDR_POSTCODE, dto.isSavePostcode());
        editPanel.add(zipEnabled, columnOne);

        postcode = generateAutoCompTextField(acm.getTagValues(TAG_ADDR_POSTCODE), dto.getPostcode());
        editPanel.add(postcode, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> postcode.setSelectedItem(selection.get(TAG_ADDR_POSTCODE))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_ADDR_POSTCODE)), columnFour);

        // street
        streetEnabled = generateCheckbox(null, dto.isSaveStreet());
        editPanel.add(streetEnabled, GBC.std().insets(5, 5, 0, 5));

        streetRadio = new JRadioButton(TAG_ADDR_STREET);
        streetRadio.setToolTipText(TAG_STREET_OR_PLACE);
        streetRadio.setSelected(dto.isTagStreet());
        streetRadio.addItemListener(new RadioChangeListener());
        editPanel.add(streetRadio, GBC.std().weight(0, 0).insets(5, 5, 0, 5));

        placeRadio = new JRadioButton("addr:place");
        placeRadio.setToolTipText(TAG_STREET_OR_PLACE);
        placeRadio.setSelected(!dto.isTagStreet());
        placeRadio.addItemListener(new RadioChangeListener());
        editPanel.add(placeRadio, GBC.std().insets(5, 5, 0, 5));

        ButtonGroup g = new ButtonGroup();
        g.add(streetRadio);
        g.add(placeRadio);

        if (dto.isTagStreet()) {
            street = generateAutoCompTextField(getPossibleStreets(), dto.getStreet());
        } else {
            street = generateAutoCompTextField(acm.getTagValues(TAG_ADDR_PLACE), dto.getStreet());
        }
        editPanel.add(street, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> updateStreetOrPlaceValues()), columnThree);

        JTextField streetOrPlace = generateTextField(getStreetOrPlaceTag());
        streetOrPlace.setPreferredSize(new Dimension(50, 24));
        editPanel.add(streetOrPlace, GBC.std().weight(0, 0).fill(GridBagConstraints.HORIZONTAL).insets(5, 5, 0, 5));

        JTextField existingStreet = generateTextField(getStreetOrPlaceValue());
        existingStreet.setPreferredSize(new Dimension(100, 24));
        editPanel.add(existingStreet, GBC.eol().weight(1, 0).insets(5, 5, 0, 5).fill(GridBagConstraints.HORIZONTAL));
        // housenumber
        housenumberEnabled = generateCheckbox(TAG_ADDR_HOUSENUMBER, dto.isSaveHousenumber());
        editPanel.add(housenumberEnabled, columnOne);

        boolean determinedIncrementNum;
        String nextHouseNumber;

        if (Boolean.TRUE.equals(forceLetterIncrement)) {
            if (HouseNumberHelper.hasLetter(dto.getHousenumber())) {
                determinedIncrementNum = true;
                nextHouseNumber = HouseNumberHelper.incrementNumberRemoveLetter(dto.getHousenumber(),
                        dto.getHousenumberChangeValue());
            } else {
                determinedIncrementNum = false;
                nextHouseNumber = HouseNumberHelper.incrementHouseNumber(dto.getHousenumber(),
                        dto.getHousenumberChangeValue(), false);
            }
        } else {
            determinedIncrementNum = forceLetterIncrement != null ? !forceLetterIncrement : dto.isIncrementNum();
            nextHouseNumber = HouseNumberHelper.incrementHouseNumber(dto.getHousenumber(),
                    dto.getHousenumberChangeValue(), determinedIncrementNum);
        }

        housenumber = generateTextField(nextHouseNumber);
        housenumber.setEditable(true);

        editPanel.add(housenumber, columnTwo);

        editPanel.add(generateAcceptButton(actionEvent -> housenumber.setText(selection.get(TAG_ADDR_HOUSENUMBER))), columnThree);
        editPanel.add(generateTextField(selection.get(TAG_ADDR_HOUSENUMBER)), columnFour);

        // increment number or letter?
        incrementNumRadio = new JRadioButton(TAG_INCREMENT_NUMBER);
        incrementNumRadio.setToolTipText(tr("increment number"));
        incrementNumRadio.setSelected(determinedIncrementNum);
        incrementNumRadio.addItemListener(new RadioChangeListener());
        // editPanel.add(incrementNumRadio, GBC.std().weight(0, 0).insets(250, 5, 0, 5));

        incrementLetterRadio = new JRadioButton(TAG_INCREMENT_LETTER);
        incrementLetterRadio.setToolTipText(tr("supports 'a', 'A', '-a', '/A', '-1', '/1'"));
        incrementLetterRadio.setSelected(!determinedIncrementNum);
        incrementLetterRadio.addItemListener(new RadioChangeListener());
        // editPanel.add(incrementLetterRadio, GBC.std().insets(5, 5, 0, 5));

        // add buttons with placeholders
        editPanel.add(Box.createHorizontalGlue(), GBC.std().weight(1, 0).fill(GBC.HORIZONTAL));
        editPanel.add(incrementNumRadio, GBC.std().insets(5, 5, 0, 5));
        editPanel.add(incrementLetterRadio, GBC.std().insets(5, 5, 0, 5));
        editPanel.add(Box.createHorizontalGlue(), GBC.eol().weight(1, 0).fill(GBC.HORIZONTAL));

        ButtonGroup g2 = new ButtonGroup();
        g2.add(incrementNumRadio);
        g2.add(incrementLetterRadio);

        // increment number
        JLabel seqLabel = new JLabel(tr("House number increment:"));
        editPanel.add(seqLabel, columnOne);

        housenumberChangeSequence = new JSlider(JSlider.HORIZONTAL, FPS_MIN, FPS_MAX, dto.getHousenumberChangeValue());
        housenumberChangeSequence.setPaintTicks(true);
        housenumberChangeSequence.setMajorTickSpacing(1);
        housenumberChangeSequence.setMinorTickSpacing(1);
        housenumberChangeSequence.setPaintLabels(true);
        housenumberChangeSequence.setSnapToTicks(true);
        editPanel.add(housenumberChangeSequence, GBC.eol().weight(1, 0).insets(20, 5, 10, 5).fill(GridBagConstraints.HORIZONTAL));

        // add listeners for building / entrance
        buildingEnabled.addItemListener(e -> updateCheckboxStates());
        houseEnabled.addItemListener(e -> updateCheckboxStates());
        entranceEnabled.addItemListener(e -> updateCheckboxStates());

        return editPanel;
    }

    // helpers for checking wether node or way is selected
    private boolean isWaySelected() {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null || ds.getSelected().isEmpty()) {
            return false;
        }
        return ds.getSelected().iterator().next() instanceof Way;
    }

    private boolean isNodeSelected() {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null || ds.getSelected().isEmpty()) {
            return false;
        }
        return ds.getSelected().iterator().next() instanceof Node;
    }

    // enable / disable checkboxes according to current selection
    private void updateCheckboxStates() {
        buildingEnabled.setEnabled(isWaySelected());
        houseEnabled.setEnabled(isWaySelected() && buildingEnabled.isSelected());
        entranceEnabled.setEnabled(isNodeSelected());
    }

    private void addSelectionListener() {
        MainApplication.getLayerManager().addActiveLayerChangeListener(e -> updateCheckboxStates());
        MainApplication.getLayerManager().getEditDataSet().addSelectionListener(e -> updateCheckboxStates());
    }


    /**
     * Generate a checkbox for applying changes
     * @param text The text to show
     * @param enabled Whether or not the checkbox is enabled
     * @return The checkbox to add
     */
    private static JCheckBox generateCheckbox(String text, boolean enabled) {
        JCheckBox checkBox = new JCheckBox(text, enabled);
        checkBox.setFocusable(false);
        checkBox.setToolTipText(APPLY_CHANGES);
        return checkBox;
    }

    /**
     * Generate an accept button
     * @param listener The listener to call when the user "accepts" a value
     * @return The button to add
     */
    private static JButton generateAcceptButton(ActionListener listener) {
        JButton button = new JButton("<");
        button.setPreferredSize(new Dimension(45, 24));
        button.setToolTipText(tr("Accept existing value"));
        button.addActionListener(listener);
        return button;
    }

    private static AutoCompComboBox<AutoCompletionItem> generateAutoCompTextField(Collection<AutoCompletionItem> tagValues, String selected) {
        AutoCompComboBox<AutoCompletionItem> comboBox = new AutoCompComboBox<>();
        comboBox.getModel().addAllElements(tagValues);
        comboBox.setPreferredSize(new Dimension(200, 24));
        comboBox.setEditable(true);
        comboBox.setSelectedItem(selected);
        return comboBox;
    }

    /**
     * Generate a non-editable text field
     * @param startingText The text to show
     * @return The text field (200x24)
     */
    private static JosmTextField generateTextField(String startingText) {
        JosmTextField textField = new JosmTextField();
        textField.setText(startingText);
        textField.setPreferredSize(new Dimension(200, 24));
        textField.setEditable(false);
        return textField;
    }

    private void acceptAllExistingValues() {
        updateStreetOrPlaceValues();
        updateIncrementNumOrLetter();
        building.setSelectedItem(selection.get(TAG_BUILDING));
        house.setText(selection.get(TAG_HOUSE));
        entrance.setSelectedItem(selection.get(TAG_ENTRANCE));
        country.setSelectedItem(selection.get(TAG_ADDR_COUNTRY));
        suburb.setSelectedItem(selection.get(TAG_ADDR_SUBURB));
        city.setSelectedItem(selection.get(TAG_ADDR_CITY));
        postcode.setSelectedItem(selection.get(TAG_ADDR_POSTCODE));
        housenumber.setText(selection.get(TAG_ADDR_HOUSENUMBER));
    }

    private void updateStreetOrPlaceValues() {
        if (selection.hasTag(TAG_ADDR_PLACE)) {
            placeRadio.setSelected(true);
            street.setSelectedItem(selection.get(TAG_ADDR_PLACE));
        } else {
            streetRadio.setSelected(true);
            street.setSelectedItem(selection.get(TAG_ADDR_STREET));
        }
    }

    private String getStreetOrPlaceValue() {
        if (selection.hasTag(TAG_ADDR_PLACE)) {
            return selection.get(TAG_ADDR_PLACE);
        } else if (selection.hasTag(TAG_ADDR_STREET)) {
            return selection.get(TAG_ADDR_STREET);
        } else {
            return "";
        }
    }

    private String getStreetOrPlaceTag() {
        if (selection.hasTag(TAG_ADDR_PLACE)) {
            return TAG_ADDR_PLACE;
        } else if (selection.hasTag(TAG_ADDR_STREET)) {
            return TAG_ADDR_STREET;
        } else {
            return "";
        }
    }

    private void updateIncrementNumOrLetter() {
        if (Boolean.TRUE.equals(forceLetterIncrement)) {
            if (HouseNumberHelper.hasLetter(loadDto().getHousenumber())) {
                incrementNumRadio.setSelected(true);
                incrementLetterRadio.setSelected(false);
            } else {
                incrementNumRadio.setSelected(false);
                incrementLetterRadio.setSelected(true);
            }
        } else if (forceLetterIncrement != null) {
            incrementNumRadio.setSelected(!forceLetterIncrement);
            incrementLetterRadio.setSelected(forceLetterIncrement);
        } else if (loadDto().isIncrementNum()) {
            incrementNumRadio.setSelected(true);
            incrementLetterRadio.setSelected(false);
        } else {
            incrementLetterRadio.setSelected(true);
            incrementNumRadio.setSelected(false); // explicit
        }
    }

    @Override
    protected void buttonAction(int buttonIndex, ActionEvent evt) {
        if (buttonIndex == 0) {
            Dto dto = new Dto();
            dto.setSaveBuilding(buildingEnabled.isSelected());
            dto.setSaveHouse(houseEnabled.isSelected());
            dto.setSaveEntrance(entranceEnabled.isSelected());
            dto.setSaveCity(cityEnabled.isSelected());
            dto.setSaveCountry(countryEnabled.isSelected());
            dto.setSaveHousenumber(housenumberEnabled.isSelected());
            dto.setSavePostcode(zipEnabled.isSelected());
            dto.setSaveStreet(streetEnabled.isSelected());
            dto.setTagStreet(streetRadio.isSelected());
            dto.setSaveSuburb(suburbEnabled.isSelected());
            dto.setIncrementNum(incrementNumRadio.isSelected());

            dto.setBuilding((String) building.getSelectedItem());
            dto.setHouse(house.getText());
            dto.setEntrance((String) entrance.getSelectedItem());
            dto.setCity(getAutoCompletingComboBoxValue(city));
            dto.setCountry(getAutoCompletingComboBoxValue(country));
            dto.setHousenumber(housenumber.getText());
            dto.setPostcode(getAutoCompletingComboBoxValue(postcode));
            dto.setStreet(getAutoCompletingComboBoxValue(street));
            dto.setSuburb(getAutoCompletingComboBoxValue(suburb));
            dto.setHousenumberChangeValue(housenumberChangeSequence.getValue());

            updateJOSMSelection(selection, dto);
            saveDto(dto);
        }
        setVisible(false);
    }

    private static String getAutoCompletingComboBoxValue(AutoCompComboBox<AutoCompletionItem> box) {
        Object item = box.getSelectedItem();
        if (item != null) {
            if (item instanceof String) {
                return (String) item;
            }
            if (item instanceof AutoCompletionItem) {
                return ((AutoCompletionItem) item).getValue();
            }
            return item.toString();
        } else {
            return "";
        }
    }

    protected void saveDto(Dto dto) {
        File fileName = new File(pluginDir + TagDialog.TEMPLATE_DATA);

        try {
            if (pluginDir.isDirectory() || pluginDir.mkdirs()) {
                try (
                        FileOutputStream file = new FileOutputStream(fileName);
                        ObjectOutputStream o = new ObjectOutputStream(file)
                ) {
                    o.writeObject(dto);
                }
            }
        } catch (IOException ex) {
            Logging.error(ex);
            if (!fileName.delete()) {
                Logging.trace("TagDialog: {0} not deleted", fileName);
            }
        }
    }

    protected void updateJOSMSelection(OsmPrimitive selection, Dto dto) {
        List<Command> commands = new ArrayList<>();

        if (dto.isSaveBuilding() && isWaySelected()) {
            String value = selection.get(TagDialog.TAG_BUILDING);
            if (value == null || !value.equals(dto.getBuilding())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_BUILDING, dto.getBuilding());
                commands.add(command);
            }
        }

        if (dto.isSaveHouse() && dto.isSaveBuilding() && isWaySelected()) {
            String value = selection.get(TagDialog.TAG_HOUSE);
            if (value == null || !value.equals(dto.getHouse())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_HOUSE, dto.getHouse());
                commands.add(command);
            }
        }

        if (dto.isSaveEntrance() && isNodeSelected()) {
            String value = selection.get(TagDialog.TAG_ENTRANCE);
            if (value == null || !value.equals(dto.getEntrance())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ENTRANCE, dto.getEntrance());
                commands.add(command);
            }
        }

        if (dto.isSaveCity()) {
            String value = selection.get(TagDialog.TAG_ADDR_CITY);
            if (value == null || !value.equals(dto.getCity())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_CITY, dto.getCity());
                commands.add(command);
            }
        }

        if (dto.isSaveCountry()) {
            String value = selection.get(TagDialog.TAG_ADDR_COUNTRY);
            if (value == null || !value.equals(dto.getCountry())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_COUNTRY, dto.getCountry());
                commands.add(command);
            }
        }

        if (dto.isSaveSuburb()) {
            String value = selection.get(TagDialog.TAG_ADDR_SUBURB);
            if (value == null || !value.equals(dto.getSuburb())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_SUBURB, dto.getSuburb());
                commands.add(command);
            }
        }

        if (dto.isSaveHousenumber()) {
            String value = selection.get(TagDialog.TAG_ADDR_HOUSENUMBER);
            if (value == null || !value.equals(dto.getHousenumber())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_HOUSENUMBER, dto.getHousenumber());
                commands.add(command);
            }
        }

        if (dto.isSavePostcode()) {
            String value = selection.get(TagDialog.TAG_ADDR_POSTCODE);
            if (value == null || !value.equals(dto.getPostcode())) {
                ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_POSTCODE, dto.getPostcode());
                commands.add(command);
            }
        }

        if (dto.isSaveStreet()) {
            if (dto.isTagStreet()) {
                String value = selection.get(TagDialog.TAG_ADDR_STREET);
                if (value == null || !value.equals(dto.getStreet())) {
                    ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_STREET, dto.getStreet());
                    commands.add(command);

                    // remove old place-tag
                    if (selection.get(TagDialog.TAG_ADDR_PLACE) != null) {
                        ChangePropertyCommand command2 = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_PLACE, null);
                        commands.add(command2);
                    }
                }
            } else {
                String value = selection.get(TagDialog.TAG_ADDR_PLACE);
                if (value == null || !value.equals(dto.getStreet())) {
                    ChangePropertyCommand command = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_PLACE, dto.getStreet());
                    commands.add(command);

                    // remove old place-tag
                    if (selection.get(TagDialog.TAG_ADDR_STREET) != null) {
                        ChangePropertyCommand command2 = new ChangePropertyCommand(selection, TagDialog.TAG_ADDR_STREET, null);
                        commands.add(command2);
                    }
                }
            }
        }

        if (!commands.isEmpty()) {
            SequenceCommand sequenceCommand = new SequenceCommand(
                 trn("Updating properties of up to {0} object",
                     "Updating properties of up to {0} objects", commands.size(), commands.size()), commands);

            // executes the commands and adds them to the undo/redo chains
            UndoRedoHandler.getInstance().add(sequenceCommand);
        }
    }

    /**
     * Generates a list of all visible names of highways in order to do autocompletion on the road name.
     * @return The possible streets for the current edit dataset
     */
    private static Collection<AutoCompletionItem> getPossibleStreets() {
        Set<AutoCompletionItem> names = new TreeSet<>();
        for (OsmPrimitive osm : MainApplication.getLayerManager().getEditDataSet().allNonDeletedPrimitives()) {
            if (osm.getKeys() != null && osm.keySet().contains("highway") && osm.keySet().contains("name")) {
                names.add(new AutoCompletionItem(osm.get("name")));
            }
        }
        return names;
    }

    private Dto loadDto() {
        Dto dto = new Dto();
        File fileName = new File(pluginDir + TagDialog.TEMPLATE_DATA);

        try {
            if (fileName.exists()) {
                try (
                        FileInputStream file = new FileInputStream(fileName);
                        ObjectInputStream o = new ObjectInputStream(file)
                ) {
                    dto = (Dto) o.readObject();
                }
            } else {
                loadExistingValuesToDto(dto);
            }
        } catch (ClassNotFoundException | IOException ex) {
            Logging.error(ex);
            if (!fileName.delete()) {
                Logging.trace("TagDialog: {0} not deleted", fileName);
            }
            loadExistingValuesToDto(dto);
        }
        dto.setHousenumberChangeValue(Utils.clamp(dto.getHousenumberChangeValue(), FPS_MIN, FPS_MAX));
        return dto;
    }

    private void loadExistingValuesToDto(Dto dto) {
        dto.setHouse(selection.get(TagDialog.TAG_HOUSE));
        dto.setCity(selection.get(TagDialog.TAG_ADDR_CITY));
        dto.setCountry(selection.get(TagDialog.TAG_ADDR_COUNTRY));
        dto.setHousenumber(selection.get(TagDialog.TAG_ADDR_HOUSENUMBER));
        dto.setPostcode(selection.get(TagDialog.TAG_ADDR_POSTCODE));
        dto.setStreet(selection.get(TagDialog.TAG_ADDR_STREET));
        dto.setSuburb(selection.get(TagDialog.TAG_ADDR_SUBURB));
    }

    class RadioChangeListener implements ItemListener {

        @Override
        public void itemStateChanged(ItemEvent e) {
            if (streetRadio.isSelected()) {
                street.getModel().addAllElements(getPossibleStreets());
            } else {
                street.getModel().addAllElements(acm.getTagValues(TAG_ADDR_PLACE));
            }
        }
    }
}
