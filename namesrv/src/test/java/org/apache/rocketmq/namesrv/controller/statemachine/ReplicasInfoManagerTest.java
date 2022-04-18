package org.apache.rocketmq.namesrv.controller.statemachine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.AlterInSyncReplicasRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.AlterInSyncReplicasResponseHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.ElectMasterRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.ElectMasterResponseHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.ErrorCodes;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.GetReplicaInfoRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.GetReplicaInfoResponseHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.RegisterBrokerRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.RegisterBrokerResponseHeader;
import org.apache.rocketmq.namesrv.controller.event.ControllerResult;
import org.apache.rocketmq.namesrv.controller.event.ElectMasterEvent;
import org.apache.rocketmq.namesrv.controller.event.EventMessage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ReplicasInfoManagerTest {
    private ReplicasInfoManager replicasInfoManager;

    @Before
    public void init() {
        this.replicasInfoManager = new ReplicasInfoManager(true);
    }

    public boolean registerNewBroker(String clusterName, String brokerName, String brokerAddress, boolean isFirstRegisteredBroker) {
        // Register new broker
        final RegisterBrokerRequestHeader registerRequest =
            new RegisterBrokerRequestHeader(clusterName, brokerName, brokerAddress);
        final ControllerResult<RegisterBrokerResponseHeader> registerResult = this.replicasInfoManager.registerBroker(registerRequest);
        apply(registerResult.getEvents());

        if (isFirstRegisteredBroker) {
            final ControllerResult<GetReplicaInfoResponseHeader> getInfoResult = this.replicasInfoManager.getReplicasInfo(new GetReplicaInfoRequestHeader(brokerName));
            final GetReplicaInfoResponseHeader replicaInfo = getInfoResult.getResponse();
            if (replicaInfo.getErrorCode() != ErrorCodes.NONE.getCode()) {
                return false;
            }
            assertEquals(replicaInfo.getMasterAddress(), brokerAddress);
            assertEquals(replicaInfo.getMasterEpoch(), 1);
            assertEquals(replicaInfo.getSyncStateSet().size(), 1);
        } else {
            final RegisterBrokerResponseHeader response = registerResult.getResponse();
            assertTrue(response.getBrokerId() > 0);
        }
        return true;
    }

    private boolean alterNewInSyncSet(String brokerName, String masterAddress, int masterEpoch, Set<String> newSyncStateSet, int syncStateSetEpoch) {
        final AlterInSyncReplicasRequestHeader alterRequest =
            new AlterInSyncReplicasRequestHeader(brokerName, masterAddress, masterEpoch, newSyncStateSet, syncStateSetEpoch);
        final ControllerResult<AlterInSyncReplicasResponseHeader> result = this.replicasInfoManager.alterSyncStateSet(alterRequest);
        apply(result.getEvents());

        final GetReplicaInfoResponseHeader replicaInfo = this.replicasInfoManager.getReplicasInfo(new GetReplicaInfoRequestHeader(brokerName)).getResponse();
        if (replicaInfo.getErrorCode() != ErrorCodes.NONE.getCode()) {
            return false;
        }
        assertArrayEquals(replicaInfo.getSyncStateSet().toArray(), newSyncStateSet.toArray());
        assertEquals(replicaInfo.getSyncStateSetEpoch(), syncStateSetEpoch + 1);
        return true;
    }

    private void apply(final List<EventMessage> events) {
        for (EventMessage event : events) {
            this.replicasInfoManager.applyEvent(event);
        }
    }


    public void mockMetaData() {
        registerNewBroker("cluster1", "broker1", "127.0.0.1:9000", true);
        registerNewBroker("cluster1", "broker1", "127.0.0.1:9001", false);
        registerNewBroker("cluster1", "broker1", "127.0.0.1:9002", false);
        final HashSet<String> newSyncStateSet = new HashSet<>();
        newSyncStateSet.add("127.0.0.1:9000");
        newSyncStateSet.add("127.0.0.1:9001");
        newSyncStateSet.add("127.0.0.1:9002");
        assertTrue(alterNewInSyncSet("broker1", "127.0.0.1:9000", 1, newSyncStateSet, 1));
    }

    @Test
    public void testElectMaster() {
        mockMetaData();
        final ElectMasterRequestHeader request = new ElectMasterRequestHeader("broker1");
        final ControllerResult<ElectMasterResponseHeader> cResult = this.replicasInfoManager.electMaster(request);
        assertEquals(cResult.getResponse().getErrorCode(), ErrorCodes.NONE.getCode());
        final ElectMasterResponseHeader response = cResult.getResponse();
        assertEquals(response.getMasterEpoch(), 2);
        assertFalse(response.getNewMasterAddress().isEmpty());
        assertNotEquals(response.getNewMasterAddress(), "127.0.0.1:9000");
    }

    @Test
    public void testAllReplicasShutdownAndRestart() {
        mockMetaData();
        final HashSet<String> newSyncStateSet = new HashSet<>();
        newSyncStateSet.add("127.0.0.1:9000");
        assertTrue(alterNewInSyncSet("broker1", "127.0.0.1:9000", 1, newSyncStateSet, 2));

        // Now we trigger electMaster api, which means the old master is shutdown and want to elect a new master.
        // However, the syncStateSet in statemachine is {"127.0.0.1:9000"}, not more replicas can be elected as master, it will be failed.
        final ElectMasterRequestHeader electRequest = new ElectMasterRequestHeader("broker1");
        final ControllerResult<ElectMasterResponseHeader> cResult = this.replicasInfoManager.electMaster(electRequest);
        final List<EventMessage> events = cResult.getEvents();
        assertEquals(events.size(), 1);
        final ElectMasterEvent event = (ElectMasterEvent) events.get(0);
        assertFalse(event.isNewMasterElected());

        apply(cResult.getEvents());

        final GetReplicaInfoResponseHeader replicaInfo = this.replicasInfoManager.getReplicasInfo(new GetReplicaInfoRequestHeader("broker1")).getResponse();
        assertEquals(replicaInfo.getMasterAddress(), "");
        assertEquals(replicaInfo.getMasterEpoch(), 2);
    }

}