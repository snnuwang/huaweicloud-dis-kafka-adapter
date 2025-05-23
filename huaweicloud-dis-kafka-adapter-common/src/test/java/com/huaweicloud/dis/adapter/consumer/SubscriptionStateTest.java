/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.dis.adapter.consumer;

import com.huaweicloud.dis.adapter.common.consumer.DisConsumerRebalanceListener;
import com.huaweicloud.dis.adapter.common.consumer.SubscriptionState;
import com.huaweicloud.dis.adapter.common.model.DisOffsetAndMetadata;
import com.huaweicloud.dis.adapter.common.model.DisOffsetResetStrategy;
import com.huaweicloud.dis.adapter.common.model.StreamPartition;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SubscriptionStateTest {

    private final SubscriptionState state = new SubscriptionState(DisOffsetResetStrategy.EARLIEST);
    private final String topic = "test";
    private final String topic1 = "test1";
    private final StreamPartition tp0 = new StreamPartition("test", 0, null);
    private final StreamPartition tp1 = new StreamPartition("test", 1, null);
    private final MockRebalanceListenerDis rebalanceListener = new MockRebalanceListenerDis();

    @Test
    public void partitionAssignment() {
        state.assignFromUser(Arrays.asList(tp0));
        assertEquals(Collections.singleton(tp0), state.assignedPartitions());
        assertFalse(state.partitionAssignmentNeeded());
        assertFalse(state.hasAllFetchPositions());
        assertTrue(state.refreshCommitsNeeded());
        state.committed(tp0, new DisOffsetAndMetadata(1));
        state.seek(tp0, 1);
        assertTrue(state.isFetchable(tp0));
        assertAllPositions(tp0, 1L);
        state.assignFromUser(Arrays.<StreamPartition>asList());
        assertTrue(state.assignedPartitions().isEmpty());
        assertFalse(state.isAssigned(tp0));
        assertFalse(state.isFetchable(tp0));
    }

    @Test
    public void partitionReset() {
        state.assignFromUser(Arrays.asList(tp0));
        state.seek(tp0, 5);
        assertEquals(5L, (long) state.position(tp0));
        state.needOffsetReset(tp0);
        assertFalse(state.isFetchable(tp0));
        assertTrue(state.isOffsetResetNeeded(tp0));
        assertEquals(null, state.position(tp0));

        // seek should clear the reset and make the partition fetchable
        state.seek(tp0, 0);
        assertTrue(state.isFetchable(tp0));
        assertFalse(state.isOffsetResetNeeded(tp0));
    }

    @Test
    public void topicSubscription() {
        state.subscribe(Arrays.asList(topic), rebalanceListener);
        assertEquals(1, state.subscription().size());
        assertTrue(state.partitionAssignmentNeeded());
        assertTrue(state.assignedPartitions().isEmpty());
        assertTrue(state.partitionsAutoAssigned());
        state.assignFromSubscribed(asList(tp0));
        state.seek(tp0, 1);
        state.committed(tp0, new DisOffsetAndMetadata(1));
        assertAllPositions(tp0, 1L);
        state.assignFromSubscribed(asList(tp1));
        assertTrue(state.isAssigned(tp1));
        assertFalse(state.partitionAssignmentNeeded());
        assertFalse(state.isAssigned(tp0));
        assertFalse(state.isFetchable(tp1));
        assertEquals(Collections.singleton(tp1), state.assignedPartitions());
    }

    @Test
    public void partitionPause() {
        state.assignFromUser(Arrays.asList(tp0));
        state.seek(tp0, 100);
        assertTrue(state.isFetchable(tp0));
        state.pause(tp0);
        assertFalse(state.isFetchable(tp0));
        state.resume(tp0);
        assertTrue(state.isFetchable(tp0));
    }

    @Test
    public void commitOffsetMetadata() {
        state.assignFromUser(Arrays.asList(tp0));
        state.committed(tp0, new DisOffsetAndMetadata(5, "hi"));

        assertEquals(5, state.committed(tp0).offset());
        assertEquals("hi", state.committed(tp0).metadata());
    }

    @Test(expected = IllegalStateException.class)
    public void invalidPositionUpdate() {
        state.subscribe(Arrays.asList(topic), rebalanceListener);
        state.assignFromSubscribed(asList(tp0));
        state.position(tp0, 0);
    }

    @Test(expected = IllegalStateException.class)
    public void cantChangePositionForNonAssignedPartition() {
        state.position(tp0, 1);
    }

    public void assertAllPositions(StreamPartition tp, Long offset) {
        assertEquals(offset.longValue(), state.committed(tp).offset());
        assertEquals(offset, state.position(tp));
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribeStreamAndPattern() {
        state.subscribe(Arrays.asList(topic), rebalanceListener);
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribePartitionAndPattern() {
        state.assignFromUser(Arrays.asList(tp0));
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribePatternAndStream() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.subscribe(Arrays.asList(topic), rebalanceListener);
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribePatternAndPartition() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.assignFromUser(Arrays.asList(tp0));
    }

    @Test
    public void patternSubscription() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.changeSubscription(Arrays.asList(topic, topic1));

        assertEquals(
                "Expected subscribed topics count is incorrect", 2, state.subscription().size());
    }

    @Test
    public void unsubscribeUserAssignment() {
        state.assignFromUser(Arrays.asList(tp0, tp1));
        state.unsubscribe();
        state.subscribe(Arrays.asList(topic), rebalanceListener);
        assertEquals(Collections.singleton(topic), state.subscription());
    }

    @Test
    public void unsubscribeUserSubscribe() {
        state.subscribe(Arrays.asList(topic), rebalanceListener);
        state.unsubscribe();
        state.assignFromUser(Arrays.asList(tp0));
        assertEquals(Collections.singleton(tp0), state.assignedPartitions());
    }

    @Test
    public void unsubscription() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.changeSubscription(Arrays.asList(topic, topic1));
        Assert.assertTrue(state.partitionAssignmentNeeded());

        state.assignFromSubscribed(asList(tp1));
        assertEquals(Collections.singleton(tp1), state.assignedPartitions());
        assertFalse(state.partitionAssignmentNeeded());

        state.unsubscribe();
        assertEquals(0, state.subscription().size());
        Assert.assertTrue(state.assignedPartitions().isEmpty());
        Assert.assertTrue(state.partitionAssignmentNeeded());

        state.assignFromUser(Arrays.asList(tp0));
        assertEquals(Collections.singleton(tp0), state.assignedPartitions());
        assertFalse(state.partitionAssignmentNeeded());

        state.unsubscribe();
        assertEquals(0, state.subscription().size());
        Assert.assertTrue(state.assignedPartitions().isEmpty());
        Assert.assertTrue(state.partitionAssignmentNeeded());
    }

    private static class MockRebalanceListenerDis implements DisConsumerRebalanceListener {
        public Collection<StreamPartition> revoked;
        public Collection<StreamPartition> assigned;
        public int revokedCount = 0;
        public int assignedCount = 0;


        @Override
        public void onPartitionsAssigned(Collection<StreamPartition> partitions) {
            this.assigned = partitions;
            assignedCount++;
        }

        @Override
        public void onPartitionsRevoked(Collection<StreamPartition> partitions) {
            this.revoked = partitions;
            revokedCount++;
        }

    }

}