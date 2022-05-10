/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.broker.hacontroller;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.common.BrokerConfig;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.Pair;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.protocol.body.SyncStateSet;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.GetReplicaInfoResponseHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.RegisterBrokerResponseHeader;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MessageStore;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.ha.autoswitch.AutoSwitchHAService;

/**
 * The manager of broker replicas, including:
 * 1.regularly syncing metadata from controllers, and changing broker roles and master if needed, both master and slave will start this timed task.
 * 2.regularly expanding and Shrinking syncStateSet, only master will start this timed task.
 */
public class ReplicasManager {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private final ScheduledExecutorService syncMetadataService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl("ReplicasManager_SyncMetadata_"));
    private final ScheduledExecutorService checkSyncStateSetService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl("ReplicasManager_CheckSyncStateSet_"));
    private final BrokerController brokerController;
    private final AutoSwitchHAService haService;
    private final HaControllerProxy proxy;
    private final String clusterName;
    private final String brokerName;
    private final String localAddress;
    private final String localHaAddress;

    private ScheduledFuture<?> checkSyncStateSetTaskFuture;
    private ScheduledFuture<?> slaveSyncFuture;

    private Set<String> syncStateSet;
    private int syncStateSetEpoch = 0;
    private BrokerRole currentRole = BrokerRole.SLAVE;
    private Long brokerId = -1L;
    private String masterAddress = "";
    private int masterEpoch = 0;

    public ReplicasManager(final BrokerController brokerController, final MessageStore messageStore) {
        this.brokerController = brokerController;
        this.haService = (AutoSwitchHAService) messageStore.getHaService();
        final BrokerConfig brokerConfig = brokerController.getBrokerConfig();
        final String controllerPaths = brokerConfig.getNamesrvAddr();
        final String[] controllers = controllerPaths.split(";");
        assert controllers.length > 0;
        this.proxy = new HaControllerProxy(brokerController.getNettyClientConfig(), Arrays.asList(controllers));
        this.syncStateSet = new HashSet<>();
        this.clusterName = brokerConfig.getBrokerClusterName();
        this.brokerName = brokerConfig.getBrokerName();
        this.localAddress = brokerController.getBrokerAddr();
        this.localHaAddress = brokerController.getHAServerAddr();
        this.haService.setLocalAddress(this.localAddress);
    }

    public boolean start() {
        if (!this.proxy.start()) {
            LOGGER.error("Failed to start controller proxy");
            return false;
        }
        // First, register this broker to controller, get brokerId and masterAddress.
        try {
            final RegisterBrokerResponseHeader registerResponse = this.proxy.registerBroker(this.clusterName, this.brokerName, this.localAddress, this.localHaAddress);
            this.brokerId = registerResponse.getBrokerId();
            final String newMasterAddress = registerResponse.getMasterAddress();
            if (StringUtils.isNoneEmpty(newMasterAddress)) {
                if (StringUtils.equals(newMasterAddress, this.localAddress)) {
                    changeToMaster(registerResponse.getMasterEpoch(), registerResponse.getSyncStateSetEpoch());
                } else {
                    changeToSlave(newMasterAddress, registerResponse.getMasterEpoch(), registerResponse.getMasterHaAddress());
                }
            }
        } catch (final Exception e) {
            LOGGER.error("Failed to register broker to controller", e);
            return false;
        }

        // Then, scheduling sync broker metadata.
        this.syncMetadataService.scheduleAtFixedRate(this::doSyncMetaData, 0, 5, TimeUnit.SECONDS);
        return true;
    }

    public void changeToMaster(final int newMasterEpoch, final int syncStateSetEpoch) {
        synchronized (this) {
            if (newMasterEpoch > this.masterEpoch) {
                LOGGER.info("Begin to change to master, brokerName:{}, replicas:{}, new Epoch:{}", this.brokerName, this.localAddress, newMasterEpoch);

                // Change record
                this.currentRole = BrokerRole.SYNC_MASTER;
                this.brokerId = MixAll.MASTER_ID;
                this.masterAddress = this.localAddress;
                this.masterEpoch = newMasterEpoch;

                // Change sync state set
                final HashSet<String> newSyncStateSet = new HashSet<>();
                newSyncStateSet.add(this.localAddress);
                changeSyncStateSet(newSyncStateSet, syncStateSetEpoch);
                startCheckSyncStateSetService();

                // Notify ha service
                this.haService.changeToMaster(newMasterEpoch);

                // Handle the slave synchronise
                handleSlaveSynchronize(BrokerRole.SYNC_MASTER);

                this.brokerController.getBrokerConfig().setBrokerId(MixAll.MASTER_ID);
                this.brokerController.getMessageStoreConfig().setBrokerRole(BrokerRole.SYNC_MASTER);

                // Last, register broker to name-srv
                try {
                    this.brokerController.registerBrokerAll(true, true, this.brokerController.getBrokerConfig().isForceRegister());
                } catch (final Throwable ignored) {
                }

                LOGGER.error("Change broker {} to master success, masterEpoch {}, syncStateSetEpoch:{}", this.localAddress, newMasterEpoch, syncStateSetEpoch);
            }
        }
    }

    public void changeToSlave(final String newMasterAddress, final int newMasterEpoch, final String masterHaAddress) {
        synchronized (this) {
            if (newMasterEpoch > this.masterEpoch) {
                LOGGER.info("Begin to change to slave, brokerName={}, replicas:{}, brokerId={}", this.brokerName, this.localAddress, this.brokerId);

                // Change record
                this.currentRole = BrokerRole.SLAVE;
                this.masterAddress = newMasterAddress;
                this.masterEpoch = newMasterEpoch;
                stopCheckSyncStateSetService();

                // Notify ha service
                this.haService.changeToSlave(newMasterAddress, masterHaAddress, newMasterEpoch, this.brokerId);

                // Change config
                this.brokerController.getBrokerConfig().setBrokerId(this.brokerId); //TO DO check
                this.brokerController.getMessageStoreConfig().setBrokerRole(BrokerRole.SLAVE);
                this.brokerController.changeSpecialServiceStatus(false);

                // Handle the slave synchronise
                handleSlaveSynchronize(BrokerRole.SLAVE);

                // Last, register broker to name-srv
                try {
                    this.brokerController.registerBrokerAll(true, true, this.brokerController.getBrokerConfig().isForceRegister());
                } catch (final Throwable ignored) {
                }
                LOGGER.error("Change broker {} to slave, newMasterAddress:{}, newMasterEpoch:{}, newMasterHaAddress:{}", this.localAddress, newMasterAddress, newMasterEpoch, masterHaAddress);
            }
        }
    }

    private void changeSyncStateSet(final Set<String> newSyncStateSet, final int newSyncStateSetEpoch) {
        synchronized (this) {
            if (newSyncStateSetEpoch > this.syncStateSetEpoch) {
                LOGGER.error("Sync state set changed from {} to {}", this.syncStateSet, newSyncStateSet);
                this.syncStateSetEpoch = newSyncStateSetEpoch;
                this.syncStateSet = new HashSet<>(newSyncStateSet);
                this.haService.setSyncStateSet(newSyncStateSet);
            }
        }
    }

    private void handleSlaveSynchronize(final BrokerRole role) {
        if (role == BrokerRole.SLAVE) {
            if (this.slaveSyncFuture != null) {
                this.slaveSyncFuture.cancel(false);
            }
            this.brokerController.getSlaveSynchronize().setMasterAddr(this.masterAddress);
            slaveSyncFuture = this.brokerController.getScheduledExecutorService().scheduleAtFixedRate(() -> {
                try {
                    brokerController.getSlaveSynchronize().syncAll();
                } catch (final Throwable e) {
                    LOGGER.error("ScheduledTask SlaveSynchronize syncAll error.", e);
                }
            }, 1000 * 3, 1000 * 10, TimeUnit.MILLISECONDS);
        } else {
            if (this.slaveSyncFuture != null) {
                this.slaveSyncFuture.cancel(false);
            }
            this.brokerController.getSlaveSynchronize().setMasterAddr(null);
        }
    }

    private void doSyncMetaData() {
        try {
            final Pair<GetReplicaInfoResponseHeader, SyncStateSet> result = this.proxy.getReplicaInfo(this.brokerName);
            final GetReplicaInfoResponseHeader info = result.getObject1();
            final SyncStateSet syncStateSet = result.getObject2();
            LOGGER.error("Sync metadata, info:{}, set:{}", info, syncStateSet);
            final String newMasterAddress = info.getMasterAddress();
            final int newMasterEpoch = info.getMasterEpoch();
            synchronized (this) {
                // Check if master changed
                if (StringUtils.isNoneEmpty(newMasterAddress) && !StringUtils.equals(this.masterAddress, newMasterAddress) && newMasterEpoch > this.masterEpoch) {
                    if (StringUtils.equals(newMasterAddress, this.localAddress)) {
                        changeToMaster(newMasterEpoch, syncStateSet.getSyncStateSetEpoch());
                    } else {
                        changeToSlave(newMasterAddress, newMasterEpoch, info.getMasterHaAddress());
                    }
                } else {
                    // Check if sync state set changed
                    if (this.currentRole == BrokerRole.SYNC_MASTER) {
                        changeSyncStateSet(syncStateSet.getSyncStateSet(), syncStateSet.getSyncStateSetEpoch());
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.error("Error happen when get broker {}'s metadata", this.brokerName, e);
        }
    }

    private void startCheckSyncStateSetService() {
        this.checkSyncStateSetTaskFuture = this.checkSyncStateSetService.scheduleAtFixedRate(() -> {
            final Set<String> newSyncStateSet = this.haService.getLatestSyncStateSet();
            newSyncStateSet.add(this.localAddress);
            synchronized (this) {
                if (this.syncStateSet != null) {
                    // Check if syncStateSet changed
                    if (this.syncStateSet.size() == newSyncStateSet.size() && this.syncStateSet.containsAll(newSyncStateSet)) {
                        return;
                    }
                }
            }
            try {
                final SyncStateSet result = this.proxy.alterSyncStateSet(this.brokerName, this.masterAddress, this.masterEpoch, newSyncStateSet, this.syncStateSetEpoch);
                changeSyncStateSet(result.getSyncStateSet(), result.getSyncStateSetEpoch());
            } catch (final Exception e) {
                LOGGER.error("Error happen when change sync state set, broker:{}, masterAddress:{}, masterEpoch, oldSyncStateSet:{}, newSyncStateSet:{}, syncStateSetEpoch:{}",
                    this.brokerName, this.masterAddress, this.masterEpoch, this.syncStateSet, newSyncStateSet, this.syncStateSetEpoch, e);
            }
        }, 0, 8, TimeUnit.SECONDS);
    }

    private void stopCheckSyncStateSetService() {
        if (this.checkSyncStateSetTaskFuture != null) {
            this.checkSyncStateSetTaskFuture.cancel(false);
            this.checkSyncStateSetTaskFuture = null;
        }
    }

    public void shutdown() {
        this.syncMetadataService.shutdown();
        this.checkSyncStateSetService.shutdown();
    }
}
