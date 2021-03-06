/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zigbee.converter;

import java.util.concurrent.ExecutionException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.zigbee.ZigBeeBindingConstants;
import org.openhab.binding.zigbee.converter.config.ZclLevelControlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zsmartsystems.zigbee.CommandResult;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.zcl.ZclAttribute;
import com.zsmartsystems.zigbee.zcl.ZclAttributeListener;
import com.zsmartsystems.zigbee.zcl.clusters.ZclLevelControlCluster;
import com.zsmartsystems.zigbee.zcl.clusters.ZclOnOffCluster;
import com.zsmartsystems.zigbee.zcl.protocol.ZclClusterType;

/**
 *
 * @author Chris Jackson - Initial Contribution
 *
 */
public class ZigBeeConverterSwitchLevel extends ZigBeeBaseChannelConverter implements ZclAttributeListener {
    private Logger logger = LoggerFactory.getLogger(ZigBeeConverterSwitchLevel.class);

    private ZclOnOffCluster clusterOnOff;
    private ZclLevelControlCluster clusterLevelControl;
    private ZclLevelControlConfig configLevelControl;

    @Override
    public boolean initializeConverter() {
        clusterLevelControl = (ZclLevelControlCluster) endpoint.getInputCluster(ZclLevelControlCluster.CLUSTER_ID);
        if (clusterLevelControl == null) {
            logger.error("{}: Error opening device level controls", endpoint.getIeeeAddress());
            return false;
        }
        clusterOnOff = (ZclOnOffCluster) endpoint.getInputCluster(ZclOnOffCluster.CLUSTER_ID);
        if (clusterLevelControl == null) {
            logger.debug("{}: Error opening device onoff controls - will use level cluster", endpoint.getIeeeAddress());
        }

        try {
            CommandResult bindResponse = clusterLevelControl.bind().get();
            if (bindResponse.isSuccess()) {
                // Configure reporting - no faster than once per second - no slower than 10 minutes.
                CommandResult reportingResponse = clusterLevelControl.setCurrentLevelReporting(1, 600, 1).get();
                if (reportingResponse.isError()) {
                    pollingPeriod = POLLING_PERIOD_HIGH;
                }
            } else {
                pollingPeriod = POLLING_PERIOD_HIGH;
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.error("{}: Exception setting reporting ", endpoint.getIeeeAddress(), e);
        }

        // Add a listener, then request the status
        clusterLevelControl.addAttributeListener(this);
        clusterLevelControl.getCurrentLevel(0);

        if (clusterOnOff != null) {
            try {
                CommandResult bindResponse = clusterOnOff.bind().get();
                if (bindResponse.isSuccess()) {
                    // Configure reporting - no faster than once per second - no slower than 10 minutes.
                    CommandResult reportingResponse = clusterOnOff.setOnOffReporting(1, 600).get();
                    if (reportingResponse.isError()) {
                        pollingPeriod = POLLING_PERIOD_HIGH;
                    }
                } else {
                    pollingPeriod = POLLING_PERIOD_HIGH;
                }
            } catch (InterruptedException | ExecutionException e) {
                logger.error("{}: Exception setting reporting ", endpoint.getIeeeAddress(), e);
            }

            // Add a listener, then request the status
            clusterOnOff.addAttributeListener(this);
            clusterOnOff.getOnOff(0);
        }

        // Create a configuration handler and get the available options
        configLevelControl = new ZclLevelControlConfig(clusterLevelControl);
        configOptions = configLevelControl.getConfiguration();

        return true;
    }

    @Override
    public void disposeConverter() {
        clusterLevelControl.removeAttributeListener(this);
    }

    @Override
    public void handleRefresh() {
        clusterLevelControl.getCurrentLevel(0);
    }

    @Override
    public void handleCommand(final Command command) {
        int level = 0;
        if (command instanceof PercentType) {
            level = ((PercentType) command).intValue();
        } else if (command instanceof OnOffType) {
            OnOffType cmdOnOff = (OnOffType) command;
            if (clusterOnOff != null) {
                if (cmdOnOff == OnOffType.ON) {
                    clusterOnOff.onCommand();
                } else {
                    clusterOnOff.offCommand();
                }
                return;
            }

            if (cmdOnOff == OnOffType.ON) {
                level = 100;
            } else {
                level = 0;
            }
        }

        clusterLevelControl.moveToLevelWithOnOffCommand((int) (level * 254.0 / 100.0 + 0.5),
                configLevelControl.getDefaultTransitionTime());
    }

    @Override
    public Channel getChannel(ThingUID thingUID, ZigBeeEndpoint endpoint) {
        if (endpoint.getInputCluster(ZclLevelControlCluster.CLUSTER_ID) == null) {
            return null;
        }
        return createChannel(thingUID, endpoint, ZigBeeBindingConstants.CHANNEL_SWITCH_LEVEL,
                ZigBeeBindingConstants.ITEM_TYPE_DIMMER, "Dimmer");
    }

    @Override
    public Configuration updateConfiguration(@NonNull Configuration configuration) {
        Configuration updatedConfiguration = new Configuration();
        configLevelControl.updateConfiguration(configuration, updatedConfiguration);

        return updatedConfiguration;
    }

    @Override
    public void attributeUpdated(ZclAttribute attribute) {
        logger.debug("{}: ZigBee attribute reports {}", endpoint.getIeeeAddress(), attribute);
        if (attribute.getCluster() == ZclClusterType.LEVEL_CONTROL
                && attribute.getId() == ZclLevelControlCluster.ATTR_CURRENTLEVEL) {
            Integer value = (Integer) attribute.getLastValue();
            if (value != null) {
                updateChannelState(new PercentType(value * 100 / 254));
            }
            return;
        }
        if (attribute.getCluster() == ZclClusterType.ON_OFF && attribute.getId() == ZclOnOffCluster.ATTR_ONOFF) {
            Boolean value = (Boolean) attribute.getLastValue();
            if (value != null && value == true) {
                updateChannelState(OnOffType.ON);
            } else {
                updateChannelState(OnOffType.OFF);
            }
        }
    }
}
