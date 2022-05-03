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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.common.EpochEntry;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.ha.FlowMonitor;
import org.apache.rocketmq.store.ha.HAClient;
import org.apache.rocketmq.store.ha.HAConnectionState;
import org.apache.rocketmq.store.ha.io.AbstractHAReader;
import org.apache.rocketmq.store.ha.io.HAWriter;

public class AutoSwitchHAClient extends ServiceThread implements HAClient {
    /**
     * Transfer header buffer size. Schema: state ordinal + maxOffset
     */
    public static final int TRANSFER_HEADER_SIZE = 4 + 8;
    private static final InternalLogger LOGGER = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static final int READ_MAX_BUFFER_SIZE = 1024 * 1024 * 4;
    private final AtomicReference<String> masterHaAddress = new AtomicReference<>();
    private final AtomicReference<String> masterAddress = new AtomicReference<>();
    private final ByteBuffer transferHeaderBuffer = ByteBuffer.allocate(TRANSFER_HEADER_SIZE);
    private final AutoSwitchHAService haService;
    private final ByteBuffer byteBufferRead = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);
    private final DefaultMessageStore messageStore;
    private final EpochFileCache epochCache;

    private SocketChannel socketChannel;
    private Selector selector;
    private AbstractHAReader haReader;
    private HAWriter haWriter;
    private FlowMonitor flowMonitor;
    /**
     * last time that slave reads date from master.
     */
    private long lastReadTimestamp;
    /**
     * last time that slave reports offset to master.
     */
    private long lastWriteTimestamp;

    private long currentReportedOffset;
    private int processPosition;
    private volatile HAConnectionState currentState;
    /**
     * Current epoch
     */
    private volatile long currentReceivedEpoch;

    /**
     * Confirm offset = min(localMaxOffset, master confirm offset).
     */
    private volatile long confirmOffset;

    public AutoSwitchHAClient(AutoSwitchHAService haService, DefaultMessageStore defaultMessageStore,
        EpochFileCache epochCache) throws IOException {
        this.haService = haService;
        this.messageStore = defaultMessageStore;
        this.epochCache = epochCache;
        init();
    }

    public void init() throws IOException {
        this.selector = RemotingUtil.openSelector();
        this.flowMonitor = new FlowMonitor(this.messageStore.getMessageStoreConfig());
        this.haReader = new HAClientReader();
        haReader.registerHook(readSize -> {
            if (readSize > 0) {
                AutoSwitchHAClient.this.flowMonitor.addByteCountTransferred(readSize);
                lastReadTimestamp = System.currentTimeMillis();
            }
        });
        this.haWriter = new HAWriter();
        haWriter.registerHook(writeSize -> {
            if (writeSize > 0) {
                lastWriteTimestamp = System.currentTimeMillis();
            }
        });
        changeCurrentState(HAConnectionState.READY);
        this.currentReceivedEpoch = -1;
        this.currentReportedOffset = 0;
        this.processPosition = 0;
        this.confirmOffset = -1;
        this.lastReadTimestamp = System.currentTimeMillis();
        this.lastWriteTimestamp = System.currentTimeMillis();
    }

    public void reOpen() throws IOException {
        shutdown();
        init();
    }

    @Override public String getServiceName() {
        return AutoSwitchHAClient.class.getSimpleName();
    }

    @Override public void updateMasterAddress(String newAddress) {
        String currentAddr = this.masterAddress.get();
        if (masterAddress.compareAndSet(currentAddr, newAddress)) {
            LOGGER.info("update master address, OLD: " + currentAddr + " NEW: " + newAddress);
        }
    }

    @Override public void updateHaMasterAddress(String newAddress) {
        String currentAddr = this.masterHaAddress.get();
        if (masterHaAddress.compareAndSet(currentAddr, newAddress)) {
            LOGGER.info("update master ha address, OLD: " + currentAddr + " NEW: " + newAddress);
        }
    }

    @Override public String getMasterAddress() {
        return this.masterAddress.get();
    }

    @Override public String getHaMasterAddress() {
        return this.masterHaAddress.get();
    }

    @Override public long getLastReadTimestamp() {
        return this.lastReadTimestamp;
    }

    @Override public long getLastWriteTimestamp() {
        return this.lastWriteTimestamp;
    }

    @Override public HAConnectionState getCurrentState() {
        return this.currentState;
    }

    @Override public void changeCurrentState(HAConnectionState haConnectionState) {
        LOGGER.info("change state to {}", haConnectionState);
        this.currentState = haConnectionState;
    }

    public void closeMasterAndWait() {
        this.closeMaster();
        this.waitForRunning(1000 * 5);
    }

    @Override public void closeMaster() {
        if (null != this.socketChannel) {
            try {
                SelectionKey sk = this.socketChannel.keyFor(this.selector);
                if (sk != null) {
                    sk.cancel();
                }

                this.socketChannel.close();
                this.socketChannel = null;

                LOGGER.info("AutoSwitchHAClient close connection with master {}", this.masterHaAddress.get());
                this.changeCurrentState(HAConnectionState.READY);
            } catch (IOException e) {
                LOGGER.warn("CloseMaster exception. ", e);
            }

            this.lastReadTimestamp = 0;
            this.processPosition = 0;

            this.byteBufferRead.position(0);
            this.byteBufferRead.limit(READ_MAX_BUFFER_SIZE);
        }
    }

    @Override public long getTransferredByteInSecond() {
        return this.flowMonitor.getTransferredByteInSecond();
    }

    @Override public void shutdown() {
        changeCurrentState(HAConnectionState.SHUTDOWN);
        // Shutdown thread firstly
        this.flowMonitor.shutdown();
        super.shutdown();

        closeMaster();
        try {
            this.selector.close();
        } catch (IOException e) {
            LOGGER.warn("Close the selector of AutoSwitchHAClient error, ", e);
        }
    }

    private boolean isTimeToReportOffset() {
        long interval = this.messageStore.now() - this.lastWriteTimestamp;
        return interval > this.messageStore.getMessageStoreConfig().getHaSendHeartbeatInterval();
    }

    private boolean sendHandshakeHeader() {
        this.transferHeaderBuffer.position(0);
        this.transferHeaderBuffer.limit(TRANSFER_HEADER_SIZE);
        this.transferHeaderBuffer.putInt(HAConnectionState.HANDSHAKE.ordinal());
        this.transferHeaderBuffer.putLong(0L);
        this.transferHeaderBuffer.flip();
        return this.haWriter.write(this.socketChannel, this.transferHeaderBuffer);
    }

    private void handshakeWithMaster() throws IOException {
        sendHandshakeHeader();
        boolean result = this.sendHandshakeHeader();
        if (!result) {
            closeMasterAndWait();
        }

        this.selector.select(5000);

        result = this.haReader.read(this.socketChannel, this.byteBufferRead);
        if (!result) {
            closeMasterAndWait();
            return;
        }
    }

    private boolean reportSlaveOffset(final long offsetToReport) {
        this.transferHeaderBuffer.position(0);
        this.transferHeaderBuffer.limit(TRANSFER_HEADER_SIZE);
        this.transferHeaderBuffer.putInt(this.currentState.ordinal());
        this.transferHeaderBuffer.putLong(offsetToReport);
        this.transferHeaderBuffer.flip();
        return this.haWriter.write(this.socketChannel, this.transferHeaderBuffer);
    }

    private boolean reportSlaveMaxOffset() {
        boolean result = true;
        final long maxPhyOffset = this.messageStore.getMaxPhyOffset();
        if (maxPhyOffset > this.currentReportedOffset) {
            this.currentReportedOffset = maxPhyOffset;
            result = reportSlaveOffset(this.currentReportedOffset);
        }
        return result;
    }

    public boolean connectMaster() throws ClosedChannelException {
        if (null == this.socketChannel) {
            String addr = this.masterHaAddress.get();
            if (addr != null) {
                SocketAddress socketAddress = RemotingUtil.string2SocketAddress(addr);
                this.socketChannel = RemotingUtil.connect(socketAddress);
                if (this.socketChannel != null) {
                    this.socketChannel.register(this.selector, SelectionKey.OP_READ);
                    LOGGER.info("AutoSwitchHAClient connect to master {}", addr);
                    changeCurrentState(HAConnectionState.HANDSHAKE);
                }
            }
            this.currentReportedOffset = this.messageStore.getMaxPhyOffset();
            this.lastReadTimestamp = System.currentTimeMillis();
        }
        return this.socketChannel != null;
    }

    private boolean transferFromMaster() throws IOException {
        boolean result;
        if (isTimeToReportOffset()) {
            LOGGER.info("Slave report current offset {}", this.currentReportedOffset);
            result = reportSlaveOffset(this.currentReportedOffset);
            if (!result) {
                return false;
            }
        }

        this.selector.select(1000);

        result = this.haReader.read(this.socketChannel, this.byteBufferRead);
        if (!result) {
            return false;
        }

        return this.reportSlaveMaxOffset();
    }

    @Override public void run() {
        LOGGER.info(this.getServiceName() + " service started");

        this.flowMonitor.start();
        while (!this.isStopped()) {
            try {
                switch (this.currentState) {
                    case SHUTDOWN:
                        return;
                    case READY:
                        // Truncate invalid msg first
                        final long truncateOffset = AutoSwitchHAClient.this.haService.truncateInvalidMsg();
                        if (truncateOffset >= 0) {
                            AutoSwitchHAClient.this.epochCache.truncateFromOffset(truncateOffset);
                        }
                        if (!connectMaster()) {
                            LOGGER.warn("AutoSwitchHAClient connect to master {} failed", this.masterHaAddress.get());
                            waitForRunning(1000 * 5);
                        }
                        continue;
                    case HANDSHAKE:
                        handshakeWithMaster();
                        continue;
                    case TRANSFER:
                        if (!transferFromMaster()) {
                            closeMasterAndWait();
                            continue;
                        }
                        break;
                    case SUSPEND:
                    default:
                        waitForRunning(1000 * 5);
                        continue;
                }
                long interval = this.messageStore.now() - this.lastReadTimestamp;
                if (interval > this.messageStore.getMessageStoreConfig().getHaHousekeepingInterval()) {
                    LOGGER.warn("AutoSwitchHAClient, housekeeping, found this connection[" + this.masterHaAddress
                        + "] expired, " + interval);
                    closeMaster();
                    LOGGER.warn("AutoSwitchHAClient, master not response some time, so close connection");
                }
            } catch (Exception e) {
                LOGGER.warn(this.getServiceName() + " service has exception. ", e);
                closeMasterAndWait();
            }
        }

    }

    /**
     * Compare the master and slave's epoch file, find consistent point, do truncate.
     */
    private boolean doTruncate(List<EpochEntry> masterEpochEntries, long masterEndOffset) {
        final EpochFileCache masterEpochCache = new EpochFileCache();
        masterEpochCache.initCacheFromEntries(masterEpochEntries);
        masterEpochCache.setLastEpochEntryEndOffset(masterEndOffset);
        final EpochFileCache localEpochCache = new EpochFileCache();
        localEpochCache.initCacheFromEntries(this.epochCache.getAllEntries());
        localEpochCache.setLastEpochEntryEndOffset(this.messageStore.getMaxPhyOffset());

        final long truncateOffset = localEpochCache.findConsistentPoint(masterEpochCache);
        if (truncateOffset >= 0) {
            if (!this.messageStore.truncateFiles(truncateOffset)) {
                LOGGER.error("Failed to truncate slave log to {}", truncateOffset);
                return false;
            }
            this.epochCache.truncateFromOffset(truncateOffset);
            LOGGER.info("Truncate slave log to {} success, change to transfer state", truncateOffset);
        }
        changeCurrentState(HAConnectionState.TRANSFER);
        this.currentReportedOffset = truncateOffset;
        if (!reportSlaveMaxOffset()) {
            LOGGER.error("AutoSwitchHAClient report max offset to master failed");
            return false;
        }
        return true;
    }

    class HAClientReader extends AbstractHAReader {

        @Override
        protected boolean processReadResult(ByteBuffer byteBufferRead) {
            int readSocketPos = byteBufferRead.position();

            while (true) {
                int diff = byteBufferRead.position() - AutoSwitchHAClient.this.processPosition;
                if (diff >= AutoSwitchHAConnection.MSG_HEADER_SIZE) {
                    int masterState = byteBufferRead.getInt(AutoSwitchHAClient.this.processPosition);
                    int bodySize = byteBufferRead.getInt(AutoSwitchHAClient.this.processPosition + 4);
                    long masterOffset = byteBufferRead.getLong(AutoSwitchHAClient.this.processPosition + 4 + 4);
                    int masterEpoch = byteBufferRead.getInt(AutoSwitchHAClient.this.processPosition + 4 + 4 + 8);
                    long confirmOffset = byteBufferRead.getLong(AutoSwitchHAClient.this.processPosition + 4 + 4 + 8 + 4);

                    if (masterState != AutoSwitchHAClient.this.currentState.ordinal()) {
                        AutoSwitchHAClient.this.processPosition += AutoSwitchHAConnection.MSG_HEADER_SIZE + bodySize;
                        AutoSwitchHAClient.this.waitForRunning(1);
                        LOGGER.error("State not matched, masterState:{}, slaveState:{}, bodySize:{}, masterOffset:{}, masterEpoch:{}, confirmOffset:{}",
                            masterState, AutoSwitchHAClient.this.currentState, bodySize, masterOffset, masterEpoch, confirmOffset);
                        return true;
                    }
                    LOGGER.info("Receive master msg, masterState:{}, bodySize:{}, masterOffset:{}, masterEpoch:{}, confirmOffset:{}",
                        HAConnectionState.values()[masterState], bodySize, masterOffset, masterEpoch, confirmOffset);

                    if (diff >= (AutoSwitchHAConnection.MSG_HEADER_SIZE + bodySize)) {
                        switch (AutoSwitchHAClient.this.currentState) {
                            case HANDSHAKE:
                                AutoSwitchHAClient.this.processPosition += AutoSwitchHAConnection.MSG_HEADER_SIZE;
                                // Truncate log
                                int entrySize = AutoSwitchHAConnection.EPOCH_ENTRY_SIZE;
                                final int entryNums = bodySize / entrySize;
                                final ArrayList<EpochEntry> epochEntries = new ArrayList<>(entryNums);
                                for (int i = 0; i < entryNums; i++) {
                                    int epoch = byteBufferRead.getInt(AutoSwitchHAClient.this.processPosition + i * entrySize);
                                    long startOffset = byteBufferRead.getLong(AutoSwitchHAClient.this.processPosition + i * entrySize + 4);
                                    epochEntries.add(new EpochEntry(epoch, startOffset));
                                }
                                byteBufferRead.position(readSocketPos);
                                AutoSwitchHAClient.this.processPosition += bodySize;
                                LOGGER.info("Receive handshake, masterMaxPosition {}, masterEpochEntries:{}, try truncate log", masterOffset, epochEntries);
                                if (!doTruncate(epochEntries, masterOffset)) {
                                    LOGGER.error("AutoSwitchHAClient truncate log failed in handshake state");
                                    return false;
                                }
                                break;
                            case TRANSFER:
                                byte[] bodyData = new byte[bodySize];
                                byteBufferRead.position(AutoSwitchHAClient.this.processPosition + AutoSwitchHAConnection.MSG_HEADER_SIZE);
                                byteBufferRead.get(bodyData);
                                byteBufferRead.position(readSocketPos);
                                AutoSwitchHAClient.this.processPosition += AutoSwitchHAConnection.MSG_HEADER_SIZE + bodySize;

                                long slavePhyOffset = AutoSwitchHAClient.this.messageStore.getMaxPhyOffset();
                                if (slavePhyOffset != 0) {
                                    if (slavePhyOffset != masterOffset) {
                                        LOGGER.error("master pushed offset not equal the max phy offset in slave, SLAVE: "
                                            + slavePhyOffset + " MASTER: " + masterOffset);
                                        return false;
                                    }
                                }

                                // If epoch changed
                                if (masterEpoch != AutoSwitchHAClient.this.currentReceivedEpoch) {
                                    AutoSwitchHAClient.this.currentReceivedEpoch = masterEpoch;
                                    AutoSwitchHAClient.this.epochCache.appendEntry(new EpochEntry(masterEpoch, masterOffset));
                                }
                                AutoSwitchHAClient.this.confirmOffset = Math.min(confirmOffset, messageStore.getMaxPhyOffset());

                                if (bodySize > 0) {
                                    final DefaultMessageStore messageStore = AutoSwitchHAClient.this.messageStore;
                                    if (messageStore.appendToCommitLog(masterOffset, bodyData, 0, bodyData.length)) {
                                        LOGGER.info("Slave append master log success, from {}, size {}, epoch:{}", masterOffset, bodySize, masterEpoch);
                                    }
                                }

                                if (!reportSlaveMaxOffset()) {
                                    LOGGER.error("AutoSwitchHAClient report max offset to master failed");
                                    return false;
                                }
                                break;
                            default:
                                break;
                        }
                        continue;
                    }
                }

                if (!byteBufferRead.hasRemaining()) {
                    byteBufferRead.position(AutoSwitchHAClient.this.processPosition);
                    byteBufferRead.compact();
                    AutoSwitchHAClient.this.processPosition = 0;
                }

                break;
            }
            return true;
        }
    }
}
