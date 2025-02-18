/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.bugs;

import static org.junit.Assert.assertEquals;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;
import javax.management.ObjectName;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.jmx.BrokerViewMBean;
import org.apache.activemq.broker.jmx.DurableSubscriptionViewMBean;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.broker.util.TimeStampingBrokerPlugin;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AMQ6636Test {

    private static BrokerService brokerService;
    private static String BROKER_ADDRESS = "tcp://localhost:0";
    private String connectionUri;

    @Before
    public void setUp() throws Exception {
    	TimeStampingBrokerPlugin tsbp = new TimeStampingBrokerPlugin();
        tsbp.setZeroExpirationOverride(1000);
        tsbp.setTtlCeiling(1000);
        tsbp.setFutureOnly(true);

        PolicyEntry defaultEntry = new PolicyEntry();
        defaultEntry.setExpireMessagesPeriod(100);

        PolicyMap policyMap = new PolicyMap();
        policyMap.setDefaultEntry(defaultEntry);

        brokerService = new BrokerService();
        brokerService.setDestinationPolicy(policyMap);
        brokerService.setPersistent(false);
        brokerService.setUseJmx(true);
        brokerService.setDeleteAllMessagesOnStartup(true);
        brokerService.setPlugins(new BrokerPlugin[] {tsbp});
        connectionUri = brokerService.addConnector(BROKER_ADDRESS).getPublishableConnectString();
        brokerService.start();
        brokerService.waitUntilStarted();
    }

    @After
    public void tearDown() throws Exception {
        brokerService.stop();
        brokerService.waitUntilStopped();
    }

    @Test(timeout = 90000)
    public void testOfflineDurableConsumerPendingQueueSize() throws Exception {

        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(connectionUri);

        Connection connection = connectionFactory.createConnection();
        connection.setClientID(getClass().getName());
        connection.start();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = session.createTopic("DurableTopic");

        // Create offline durable subscriber
        MessageConsumer consumer = session.createDurableSubscriber((Topic) destination, "EnqueueSub");
        consumer.close();

        final BrokerViewMBean brokerView = brokerService.getAdminView();
        ObjectName subName = brokerView.getDurableTopicSubscribers()[0];

        final DurableSubscriptionViewMBean sub = (DurableSubscriptionViewMBean)
            brokerService.getManagementContext().newProxyInstance(subName, DurableSubscriptionViewMBean.class, true);

        assertEquals(0, sub.getEnqueueCounter());
        assertEquals(0, sub.getDequeueCounter());
        assertEquals(0, sub.getPendingQueueSize());
        assertEquals(0, sub.getDispatchedCounter());
        assertEquals(0, sub.getDispatchedQueueSize());

        // Send 2000 messages
        MessageProducer producer = session.createProducer(destination);
        for (int i = 0; i < 2000; i++) {
            producer.send(session.createMessage());
        }
        producer.close();
        
        Thread.sleep((long) (1000 * 1.5 * 10)); // wait at least of the TTL timeout + few message expiration periods

        assertEquals(2000, sub.getEnqueueCounter());
        assertEquals(0, sub.getDequeueCounter());
        assertEquals(0, sub.getPendingQueueSize());

        session.close();
        connection.close();
    }
}