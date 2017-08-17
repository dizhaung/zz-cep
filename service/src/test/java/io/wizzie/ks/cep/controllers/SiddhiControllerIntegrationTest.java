package io.wizzie.ks.cep.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.wizzie.ks.cep.model.*;
import io.wizzie.ks.cep.serializers.JsonDeserializer;
import io.wizzie.ks.cep.serializers.JsonSerializer;
import kafka.utils.MockTime;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.integration.utils.EmbeddedKafkaCluster;
import org.apache.kafka.streams.integration.utils.IntegrationTestUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class SiddhiControllerIntegrationTest {
    private final static int NUM_BROKERS = 1;

    @ClassRule
    public static EmbeddedKafkaCluster CLUSTER = new EmbeddedKafkaCluster(NUM_BROKERS);
    private final static MockTime MOCK_TIME = CLUSTER.time;

    private static final int REPLICATION_FACTOR = 1;

    @BeforeClass
    public static void startKafkaCluster() throws Exception {
        // inputs
        CLUSTER.createTopic("input1", 1, REPLICATION_FACTOR);
        CLUSTER.createTopic("input2", 1, REPLICATION_FACTOR);


        // sinks
        CLUSTER.createTopic("output1", 1, REPLICATION_FACTOR);
    }

    @Test
    public void SiddhiControllerAddTwoStreamTest() throws InterruptedException {

        String jsonData = "{\"attributeName\":\"VALUE\"}";
        String jsonData2 = "{\"attributeName\":\"VALUE2\"}";


        KeyValue<String, Map<String, Object>> kvStream1 = null;

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            kvStream1 = new KeyValue<>("KEY_A", objectMapper.readValue(jsonData, Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }

        KeyValue<String, Map<String, Object>> kvStream2 = null;

        try {
            kvStream2 = new KeyValue<>("KEY_A", objectMapper.readValue(jsonData2, Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Properties producerConfig = new Properties();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serdes.String().serializer().getClass());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);


        SiddhiController siddhiController = SiddhiController.getInstance();

        Properties  consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", CLUSTER.bootstrapServers());
        consumerProperties.put("group.id", "cep");
        consumerProperties.put("enable.auto.commit", "true");
        consumerProperties.put("auto.commit.interval.ms", "1000");
        consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
        //Property just needed for testing.
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Properties producerProperties = new Properties();
        producerProperties.put("bootstrap.servers", CLUSTER.bootstrapServers());
        producerProperties.put("acks", "all");
        producerProperties.put("retries", 0);
        producerProperties.put("batch.size", 16384);
        producerProperties.put("linger.ms", 1);
        producerProperties.put("buffer.memory", 33554432);
        producerProperties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProperties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");


        siddhiController.initKafkaController(consumerProperties, producerProperties);

        //Add Sources and Sinks Definition

        SourceModel sourceModel = new SourceModel("stream", "input1");
        List<SourceModel> sourceModelList = new LinkedList<>();
        sourceModelList.add(sourceModel);

        SinkModel sinkModel = new SinkModel("streamoutput", "output1");
        List<SinkModel> sinkModelList = new LinkedList<>();
        sinkModelList.add(sinkModel);


        //////////////////////////////////

        //Add Rule Definition

        String id = "rule2";
        String version = "v1";
        String executionPlan = "from stream select * insert into streamoutput";

        StreamMapModel streamMapModel = new StreamMapModel(sourceModelList, sinkModelList);

        RuleModel ruleModelObject = new RuleModel(id, version, streamMapModel, executionPlan);

        List<RuleModel> ruleModelList = new LinkedList<>();
        ruleModelList.add(ruleModelObject);


        List<StreamModel> streamsModel = Arrays.asList(
                new StreamModel("stream", Arrays.asList(
                        new AttributeModel("attributeName", "string")
                )), new StreamModel("stream2", Arrays.asList(
                        new AttributeModel("attributeName", "string")
                )));

        //////////////////////////////////


        ProcessingModel processingModel = new ProcessingModel(ruleModelList, streamsModel);

        siddhiController.addProcessingDefinition(processingModel);
        siddhiController.generateExecutionPlans();
        /////////////////////////////////

        Properties consumerConfigA = new Properties();
        consumerConfigA.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        consumerConfigA.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-consumer-A");
        consumerConfigA.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfigA.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerConfigA.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");


        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("attributeName", "VALUE");

        Map<String, Object> expectedData2 = new HashMap<>();
        expectedData2.put("attributeName", "VALUE2");

        KeyValue<String, Map<String, Object>> expectedDataKv = new KeyValue<>(null, expectedData);
        KeyValue<String, Map<String, Object>> expectedDataKv2 = new KeyValue<>(null, expectedData2);


        try {
            //Thread.sleep(10000);
            System.out.println("Producing KVs: " + kvStream1 + kvStream2);
            IntegrationTestUtils.produceKeyValuesSynchronously("input1", Collections.singletonList(kvStream1), producerConfig, MOCK_TIME);
            IntegrationTestUtils.produceKeyValuesSynchronously("input1", Collections.singletonList(kvStream2), producerConfig, MOCK_TIME);

        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


        List<KeyValue<String, Map>> receivedMessagesFromOutput1 = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfigA, "output1", 1);
        System.out.println("Received after Siddhi: " + receivedMessagesFromOutput1);
        assertEquals(Arrays.asList(expectedDataKv,expectedDataKv2), receivedMessagesFromOutput1);

    }


    @Test
    public void SiddhiControllerAddOneStreamTest() throws InterruptedException {

        String jsonData = "{\"attributeName\":\"VALUE\"}";

        KeyValue<String, Map<String, Object>> kvStream1 = null;

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            kvStream1 = new KeyValue<>("KEY_A", objectMapper.readValue(jsonData, Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Properties producerConfig = new Properties();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serdes.String().serializer().getClass());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);


        SiddhiController siddhiController = SiddhiController.getInstance();

        Properties  consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", CLUSTER.bootstrapServers());
        consumerProperties.put("group.id", "cep");
        consumerProperties.put("enable.auto.commit", "true");
        consumerProperties.put("auto.commit.interval.ms", "1000");
        consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
        //Property just needed for testing.
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Properties producerProperties = new Properties();
        producerProperties.put("bootstrap.servers", CLUSTER.bootstrapServers());
        producerProperties.put("acks", "all");
        producerProperties.put("retries", 0);
        producerProperties.put("batch.size", 16384);
        producerProperties.put("linger.ms", 1);
        producerProperties.put("buffer.memory", 33554432);
        producerProperties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProperties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");


        siddhiController.initKafkaController(consumerProperties, producerProperties);

        //Add Sources and Sinks Definition

        SourceModel sourceModel = new SourceModel("stream", "input1");
        List<SourceModel> sourceModelList = new LinkedList<>();
        sourceModelList.add(sourceModel);

        SinkModel sinkModel = new SinkModel("streamoutput", "output1");
        List<SinkModel> sinkModelList = new LinkedList<>();
        sinkModelList.add(sinkModel);


        //////////////////////////////////

        //Add Rule Definition

        String id = "rule1";
        String version = "v1";
        String executionPlan = "from stream select * insert into streamoutput";

        StreamMapModel streamMapModel = new StreamMapModel(Arrays.asList(sourceModel), Arrays.asList(sinkModel));

        RuleModel ruleModelObject = new RuleModel(id, version, streamMapModel, executionPlan);

        List<RuleModel> ruleModelList = new LinkedList<>();
        ruleModelList.add(ruleModelObject);


        List<StreamModel> streamsModel = Arrays.asList(
                new StreamModel("stream", Arrays.asList(
                        new AttributeModel("attributeName", "string")
                )));

        //////////////////////////////////


        ProcessingModel processingModel = new ProcessingModel(ruleModelList, streamsModel);

        siddhiController.addProcessingDefinition(processingModel);
        siddhiController.generateExecutionPlans();
        /////////////////////////////////

        Properties consumerConfigA = new Properties();
        consumerConfigA.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        consumerConfigA.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-consumer-A");
        consumerConfigA.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfigA.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerConfigA.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");


        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("attributeName", "VALUE");

        KeyValue<String, Map<String, Object>> expectedDataKv = new KeyValue<>(null, expectedData);

        try {
            //Thread.sleep(10000);
            System.out.println("Producing KV: " + kvStream1);
            IntegrationTestUtils.produceKeyValuesSynchronously("input1", Collections.singletonList(kvStream1), producerConfig, MOCK_TIME);
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


        List<KeyValue<String, Map>> receivedMessagesFromOutput1 = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfigA, "output1", 1);
        System.out.println("Received after Siddhi: " + receivedMessagesFromOutput1);
        assertEquals(Collections.singletonList(expectedDataKv), receivedMessagesFromOutput1);

    }


    @Test
    public void SiddhiControllerAddTwoRulesDifferentStreamsTest() throws InterruptedException {

        String jsonData = "{\"attributeName\":\"VALUE\"}";
        String jsonData2 = "{\"attributeName\":\"VALUE2\"}";


        KeyValue<String, Map<String, Object>> kvStream1 = null;

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            kvStream1 = new KeyValue<>("KEY_A", objectMapper.readValue(jsonData, Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }

        KeyValue<String, Map<String, Object>> kvStream2 = null;

        try {
            kvStream2 = new KeyValue<>("KEY_A", objectMapper.readValue(jsonData2, Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Properties producerConfig = new Properties();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serdes.String().serializer().getClass());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);


        SiddhiController siddhiController = SiddhiController.getInstance();

        Properties  consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", CLUSTER.bootstrapServers());
        consumerProperties.put("group.id", "cep");
        consumerProperties.put("enable.auto.commit", "true");
        consumerProperties.put("auto.commit.interval.ms", "1000");
        consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
        //Property just needed for testing.
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Properties producerProperties = new Properties();
        producerProperties.put("bootstrap.servers", CLUSTER.bootstrapServers());
        producerProperties.put("acks", "all");
        producerProperties.put("retries", 0);
        producerProperties.put("batch.size", 16384);
        producerProperties.put("linger.ms", 1);
        producerProperties.put("buffer.memory", 33554432);
        producerProperties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProperties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");


        siddhiController.initKafkaController(consumerProperties, producerProperties);

        //Add Sources and Sinks Definition

        SourceModel sourceModel = new SourceModel("stream", "input1");
        List<SourceModel> sourceModelList = new LinkedList<>();
        sourceModelList.add(sourceModel);

        SinkModel sinkModel = new SinkModel("streamoutput", "output1");
        List<SinkModel> sinkModelList = new LinkedList<>();
        sinkModelList.add(sinkModel);


        //////////////////////////////////

        //Add Rule Definition

        String id = "rule2";
        String version = "v1";
        String executionPlan = "from stream select * insert into streamoutput";

        StreamMapModel streamMapModel = new StreamMapModel(sourceModelList, sinkModelList);

        RuleModel ruleModelObject = new RuleModel(id, version, streamMapModel, executionPlan);


        String id2 = "rule3";
        String version2 = "v1";
        String executionPlan2 = "from stream select * insert into streamoutput";

        StreamMapModel streamMapModel2 = new StreamMapModel(sourceModelList, sinkModelList);

        RuleModel ruleModelObject2 = new RuleModel(id2, version2, streamMapModel2, executionPlan2);

        List<RuleModel> ruleModelList = new LinkedList<>();
        ruleModelList.add(ruleModelObject);
        ruleModelList.add(ruleModelObject2);


        List<StreamModel> streamsModel = Arrays.asList(
                new StreamModel("stream", Arrays.asList(
                        new AttributeModel("attributeName", "string")
                )), new StreamModel("stream2", Arrays.asList(
                        new AttributeModel("attributeName", "string")
                )));

        //////////////////////////////////


        ProcessingModel processingModel = new ProcessingModel(ruleModelList, streamsModel);

        siddhiController.addProcessingDefinition(processingModel);
        siddhiController.generateExecutionPlans();
        /////////////////////////////////

        Properties consumerConfigA = new Properties();
        consumerConfigA.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        consumerConfigA.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-consumer-A");
        consumerConfigA.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfigA.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerConfigA.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");


        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("attributeName", "VALUE");

        Map<String, Object> expectedData2 = new HashMap<>();
        expectedData2.put("attributeName", "VALUE2");

        KeyValue<String, Map<String, Object>> expectedDataKv = new KeyValue<>(null, expectedData);
        KeyValue<String, Map<String, Object>> expectedDataKv2 = new KeyValue<>(null, expectedData2);


        try {
            //Thread.sleep(10000);
            System.out.println("Producing KVs: " + kvStream1 + kvStream2);
            IntegrationTestUtils.produceKeyValuesSynchronously("input1", Collections.singletonList(kvStream1), producerConfig, MOCK_TIME);
            IntegrationTestUtils.produceKeyValuesSynchronously("input1", Collections.singletonList(kvStream2), producerConfig, MOCK_TIME);

        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


        List<KeyValue<String, Map>> receivedMessagesFromOutput1 = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfigA, "output1", 1);
        System.out.println("Received after Siddhi: " + receivedMessagesFromOutput1);
        assertEquals(Arrays.asList(expectedDataKv, expectedDataKv, expectedDataKv2, expectedDataKv2), receivedMessagesFromOutput1);

    }

    @AfterClass
    public static void stop() {
        CLUSTER.stop();
    }


}