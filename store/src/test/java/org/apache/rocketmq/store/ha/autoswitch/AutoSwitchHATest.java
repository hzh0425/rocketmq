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

package org.apache.rocketmq.store.ha.autoswitch;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.common.BrokerConfig;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExtBrokerInner;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.GetMessageResult;
import org.apache.rocketmq.store.GetMessageStatus;
import org.apache.rocketmq.store.MappedFileQueue;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.apache.rocketmq.store.logfile.MappedFile;
import org.apache.rocketmq.store.stats.BrokerStatsManager;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoSwitchHATest {
    private final String StoreMessage = "Once, there was a chance for me!";
    private final int defaultMappedFileSize = 1024 * 1024;
    private int QUEUE_TOTAL = 100;
    private AtomicInteger QueueId = new AtomicInteger(0);
    private SocketAddress BornHost;
    private SocketAddress StoreHost;
    private byte[] MessageBody;

    private DefaultMessageStore messageStore1;
    private DefaultMessageStore messageStore2;
    private DefaultMessageStore messageStore3;
    private MessageStoreConfig storeConfig1;
    private MessageStoreConfig storeConfig2;
    private MessageStoreConfig storeConfig3;
    private String store1HaAddress;
    private String store2HaAddress;
    private String store3HaAddress;

    private BrokerStatsManager brokerStatsManager = new BrokerStatsManager("simpleTest", true);
    private String storePathRootParentDir = System.getProperty("user.home") + File.separator +
        UUID.randomUUID().toString().replace("-", "");
    private String storePathRootDir = storePathRootParentDir + File.separator + "store";

    public void init(int mappedFileSize) throws Exception {
        QUEUE_TOTAL = 1;
        MessageBody = StoreMessage.getBytes();
        StoreHost = new InetSocketAddress(InetAddress.getLocalHost(), 8123);
        BornHost = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
        storeConfig1 = new MessageStoreConfig();
        storeConfig1.setBrokerRole(BrokerRole.SYNC_MASTER);
        storeConfig1.setStorePathRootDir(storePathRootDir + File.separator + "broker1");
        storeConfig1.setStorePathCommitLog(storePathRootDir + File.separator + "broker1" + File.separator + "commitlog");
        storeConfig1.setStorePathEpochFile(storePathRootDir + File.separator + "broker1" + File.separator + "EpochFileCache");
        storeConfig1.setTotalReplicas(3);
        storeConfig1.setInSyncReplicas(2);
        storeConfig1.setStartupControllerMode(true);
        buildMessageStoreConfig(storeConfig1, mappedFileSize);
        this.store1HaAddress = "127.0.0.1:10912";

        storeConfig2 = new MessageStoreConfig();
        storeConfig2.setBrokerRole(BrokerRole.SLAVE);
        storeConfig2.setStorePathRootDir(storePathRootDir + File.separator + "broker2");
        storeConfig2.setStorePathCommitLog(storePathRootDir + File.separator + "broker2" + File.separator + "commitlog");
        storeConfig2.setStorePathEpochFile(storePathRootDir + File.separator + "broker2" + File.separator + "EpochFileCache");
        storeConfig2.setHaListenPort(10943);
        storeConfig2.setTotalReplicas(3);
        storeConfig2.setInSyncReplicas(2);
        storeConfig2.setStartupControllerMode(true);
        buildMessageStoreConfig(storeConfig2, mappedFileSize);
        this.store2HaAddress = "127.0.0.1:10943";

        messageStore1 = buildMessageStore(storeConfig1, 0L);
        messageStore2 = buildMessageStore(storeConfig2, 1L);

        storeConfig3 = new MessageStoreConfig();
        storeConfig3.setBrokerRole(BrokerRole.SLAVE);
        storeConfig3.setStorePathRootDir(storePathRootDir + File.separator + "broker3");
        storeConfig3.setStorePathCommitLog(storePathRootDir + File.separator + "broker3" + File.separator + "commitlog");
        storeConfig3.setStorePathEpochFile(storePathRootDir + File.separator + "broker3" + File.separator + "EpochFileCache");
        storeConfig3.setHaListenPort(10980);
        storeConfig3.setTotalReplicas(3);
        storeConfig3.setInSyncReplicas(2);
        storeConfig3.setStartupControllerMode(true);
        buildMessageStoreConfig(storeConfig3, mappedFileSize);
        messageStore3 = buildMessageStore(storeConfig3, 3L);
        this.store3HaAddress = "127.0.0.1:10980";

        assertTrue(messageStore1.load());
        assertTrue(messageStore2.load());
        assertTrue(messageStore3.load());
        messageStore1.start();
        messageStore2.start();
        messageStore3.start();
    }

    private void changeMasterAndPutMessage(DefaultMessageStore master, MessageStoreConfig masterConfig,
        DefaultMessageStore slave, long slaveId, MessageStoreConfig slaveConfig, int epoch, String masterHaAddress,
        int totalPutMessageNums) throws Exception {

        // Change role
        slaveConfig.setBrokerRole(BrokerRole.SLAVE);
        masterConfig.setBrokerRole(BrokerRole.SYNC_MASTER);
        slave.getHaService().changeToSlave("", epoch, slaveId);
        slave.getHaService().updateHaMasterAddress(masterHaAddress);
        master.getHaService().changeToMaster(epoch);
        Thread.sleep(6000);

        // Put message on master
        for (int i = 0; i < totalPutMessageNums; i++) {
            master.putMessage(buildMessage());
        }
        Thread.sleep(200);
    }

    private void checkMessage(final DefaultMessageStore messageStore, int totalMsgs, int startOffset) {
        for (long i = 0; i < totalMsgs; i++) {
            GetMessageResult result = messageStore.getMessage("GROUP_A", "FooBar", 0, startOffset + i, 1024 * 1024, null);
            assertThat(result).isNotNull();
            if (!GetMessageStatus.FOUND.equals(result.getStatus())) {
                System.out.println("Failed i :" + i);
            }
            assertTrue(GetMessageStatus.FOUND.equals(result.getStatus()));
            result.release();
        }
    }

    @Test
    public void testChangeRoleManyTimes() throws Exception {
        // Step1, change store1 to master, store2 to follower
        init(defaultMappedFileSize);
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 1, store1HaAddress, 10);
        checkMessage(this.messageStore2, 10, 0);

        // Step2, change store1 to follower, store2 to master, epoch = 2
        changeMasterAndPutMessage(this.messageStore2, this.storeConfig2, this.messageStore1, 1, this.storeConfig1, 2, store2HaAddress, 10);
        checkMessage(this.messageStore1, 20, 0);

        // Step3, change store2 to follower, store1 to master, epoch = 3
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 3, store1HaAddress, 10);
        checkMessage(this.messageStore2, 30, 0);
    }

    @Test
    public void testAddBroker() throws Exception {
        // Step1: broker1 as leader, broker2 as follower
        init(defaultMappedFileSize);
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 1, store1HaAddress, 10);
        checkMessage(this.messageStore2, 10, 0);

        // Step2: add new broker3, link to broker1
        messageStore3.getHaService().changeToSlave("", 1, 3L);
        messageStore3.getHaService().updateHaMasterAddress("127.0.0.1:10912");
        Thread.sleep(6000);
        checkMessage(messageStore3, 10, 0);
    }

    @Test
    public void testTruncateEpochLogAndAddBroker() throws Exception {
        // Noted that 10 msg 's total size = 1570, and if init the mappedFileSize = 1700, one file only be used to store 10 msg.
        init(1700);

        // Step1: broker1 as leader, broker2 as follower, append 2 epoch, each epoch will be stored on one file(Because fileSize = 1700, which only can hold 10 msgs);
        // Master: <Epoch1, 0, 1570> <Epoch2, 1570, 3270>

        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 1, store1HaAddress, 10);
        checkMessage(this.messageStore2, 10, 0);
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 2, store1HaAddress, 10);
        checkMessage(this.messageStore2, 20, 0);

        // Step2: Check file position, each epoch will be stored on one file(Because fileSize = 1700, which equal to 10 msg size);
        // So epoch1 was stored in firstFile, epoch2 was stored in second file, the lastFile was empty.
        final MappedFileQueue fileQueue = this.messageStore1.getCommitLog().getMappedFileQueue();
        assertEquals(2, fileQueue.getTotalFileSize() / 1700);

        // Step3: truncate epoch1's log (truncateEndOffset = 1570), which means we should delete the first file directly.
        final MappedFile firstFile = this.messageStore1.getCommitLog().getMappedFileQueue().getFirstMappedFile();
        firstFile.shutdown(1000);
        fileQueue.retryDeleteFirstFile(1000);
        assertEquals(this.messageStore1.getCommitLog().getMinOffset(), 1700);
        checkMessage(this.messageStore1, 10, 10);

        final AutoSwitchHAService haService = (AutoSwitchHAService) this.messageStore1.getHaService();
        haService.truncateEpochFilePrefix(1570);

        // Step4: add broker3 as slave, only have 10 msg from offset 10;
        messageStore3.getHaService().changeToSlave("", 2, 3L);
        messageStore3.getHaService().updateHaMasterAddress(store1HaAddress);
        Thread.sleep(6000);

        checkMessage(messageStore3, 10, 10);
    }

    @Test
    public void testTruncateEpochLogAndChangeMaster() throws Exception {
        // Noted that 10 msg 's total size = 1570, and if init the mappedFileSize = 1700, one file only be used to store 10 msg.
        init(1700);

        // Step1: broker1 as leader, broker2 as follower, append 2 epoch, each epoch will be stored on one file(Because fileSize = 1700, which only can hold 10 msgs);
        // Master: <Epoch1, 0, 1570> <Epoch2, 1570, 3270>

        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 1, store1HaAddress, 10);
        checkMessage(this.messageStore2, 10, 0);
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 2, store1HaAddress, 10);
        checkMessage(this.messageStore2, 20, 0);

        // Step2: Check file position, each epoch will be stored on one file(Because fileSize = 1700, which equal to 10 msg size);
        // So epoch1 was stored in firstFile, epoch2 was stored in second file, the lastFile was empty.
        final MappedFileQueue fileQueue = this.messageStore1.getCommitLog().getMappedFileQueue();
        assertEquals(2, fileQueue.getTotalFileSize() / 1700);

        // Step3: truncate epoch1's log (truncateEndOffset = 1570), which means we should delete the first file directly.
        final MappedFile firstFile = this.messageStore1.getCommitLog().getMappedFileQueue().getFirstMappedFile();
        firstFile.shutdown(1000);
        fileQueue.retryDeleteFirstFile(1000);
        assertEquals(this.messageStore1.getCommitLog().getMinOffset(), 1700);

        final AutoSwitchHAService haService = (AutoSwitchHAService) this.messageStore1.getHaService();
        haService.truncateEpochFilePrefix(1570);
        checkMessage(this.messageStore1, 10, 10);

        // Step4: add broker3 as slave
        messageStore3.getHaService().changeToSlave("", 2, 3L);
        messageStore3.getHaService().updateHaMasterAddress(store1HaAddress);
        Thread.sleep(6000);
        checkMessage(messageStore3, 10, 10);

        // Step5: change broker2 as leader, broker3 as follower
        changeMasterAndPutMessage(this.messageStore2, this.storeConfig2, this.messageStore3, 3, this.storeConfig3, 3, this.store2HaAddress, 10);
        checkMessage(messageStore3, 20, 10);

        // Step6, let broker1 link to broker2, it should sync log from epoch3.
        this.storeConfig1.setBrokerRole(BrokerRole.SLAVE);
        this.messageStore1.getHaService().changeToSlave("", 3, 1L);
        this.messageStore1.getHaService().updateHaMasterAddress(this.store2HaAddress);
        Thread.sleep(6000);
        checkMessage(messageStore1, 20, 0);
    }


    @Test
    public void testAddBrokerAndSyncFromLastFile() throws Exception {
        init(1700);

        // Step1: broker1 as leader, broker2 as follower, append 2 epoch, each epoch will be stored on one file(Because fileSize = 1700, which only can hold 10 msgs);
        // Master: <Epoch1, 0, 1570> <Epoch2, 1570, 3270>
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 1, store1HaAddress, 10);
        checkMessage(this.messageStore2, 10, 0);
        changeMasterAndPutMessage(this.messageStore1, this.storeConfig1, this.messageStore2, 2, this.storeConfig2, 2, store1HaAddress, 10);
        checkMessage(this.messageStore2, 20, 0);


        // Step2: restart broker3
        messageStore3.shutdown();
        messageStore3.destroy();

        storeConfig3.setSyncFromLastFile(true);
        messageStore3 = buildMessageStore(storeConfig3, 3L);
        assertTrue(messageStore3.load());
        messageStore3.start();

        // Step2: add new broker3, link to broker1. because broker3 request sync from lastFile, so it only synced 10 msg from offset 10;
        messageStore3.getHaService().changeToSlave("", 2, 3L);
        messageStore3.getHaService().updateHaMasterAddress("127.0.0.1:10912");
        Thread.sleep(6000);
        checkMessage(messageStore3, 10, 10);
    }


    @After
    public void destroy() throws Exception {
        Thread.sleep(5000L);
        messageStore2.shutdown();
        messageStore2.destroy();
        messageStore1.shutdown();
        messageStore1.destroy();
        messageStore3.shutdown();
        messageStore3.destroy();
        File file = new File(storePathRootParentDir);
        UtilAll.deleteFile(file);
    }

    private DefaultMessageStore buildMessageStore(MessageStoreConfig messageStoreConfig,
        long brokerId) throws Exception {
        BrokerConfig brokerConfig = new BrokerConfig();
        brokerConfig.setBrokerId(brokerId);
        return new DefaultMessageStore(messageStoreConfig, brokerStatsManager, null, brokerConfig);
    }

    private void buildMessageStoreConfig(MessageStoreConfig messageStoreConfig, int mappedFileSize) {
        messageStoreConfig.setMappedFileSizeCommitLog(mappedFileSize);
        messageStoreConfig.setMappedFileSizeConsumeQueue(1024 * 1024);
        messageStoreConfig.setMaxHashSlotNum(10000);
        messageStoreConfig.setMaxIndexNum(100 * 100);
        messageStoreConfig.setFlushDiskType(FlushDiskType.SYNC_FLUSH);
        messageStoreConfig.setFlushIntervalConsumeQueue(1);
    }

    private MessageExtBrokerInner buildMessage() {
        MessageExtBrokerInner msg = new MessageExtBrokerInner();
        msg.setTopic("FooBar");
        msg.setTags("TAG1");
        msg.setBody(MessageBody);
        msg.setKeys(String.valueOf(System.currentTimeMillis()));
        msg.setQueueId(Math.abs(QueueId.getAndIncrement()) % QUEUE_TOTAL);
        msg.setSysFlag(0);
        msg.setBornTimestamp(System.currentTimeMillis());
        msg.setStoreHost(StoreHost);
        msg.setBornHost(BornHost);
        msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));
        return msg;
    }
}
