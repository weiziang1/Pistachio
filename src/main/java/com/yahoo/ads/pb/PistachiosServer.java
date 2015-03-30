/*
 * Copyright 2014 Yahoo! Inc. Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or
 * agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.ads.pb;

import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServer.Args;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TSSLTransportFactory;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TSSLTransportFactory.TSSLTransportParameters;
import java.nio.ByteBuffer;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import com.yahoo.ads.pb.store.StorePartition;
import com.ibm.icu.util.ByteArrayWrapper;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.yahoo.ads.pb.store.TKStoreFactory;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.codahale.metrics.JmxReporter;


import java.net.InetAddress;
import com.yahoo.ads.pb.store.TLongKyotoCabinetStore;
import com.yahoo.ads.pb.kafka.KeyValue;
import com.yahoo.ads.pb.helix.PartitionHandler;
import com.yahoo.ads.pb.helix.PartitionHandlerFactory;
import com.yahoo.ads.pb.helix.BootstrapOnlineOfflineStateModel;
import com.yahoo.ads.pb.helix.BootstrapOnlineOfflineStateModelFactory;
import com.yahoo.ads.pb.helix.BootstrapPartitionHandler;
import com.yahoo.ads.pb.helix.HelixPartitionManager;
import com.yahoo.ads.pb.helix.HelixPartitionSpectator;
import com.yahoo.ads.pb.network.netty.NettyPistachioServer;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.HelixManager;
import org.apache.helix.InstanceType;
import org.apache.helix.controller.GenericHelixController;


//import com.yahoo.ads.pb.platform.perf.IncrementCounter;
//import com.yahoo.ads.pb.platform.perf.InflightCounter;
import com.yahoo.ads.pb.util.ConfigurationManager;
import com.yahoo.ads.pb.util.NativeUtils;
import com.yahoo.ads.pb.store.ValueOffset;
import com.yahoo.ads.pb.customization.CustomizationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.configuration.Configuration;
import com.google.common.base.Joiner.MapJoiner;
import com.google.common.base.Joiner;
import com.yahoo.ads.pb.customization.StoreCallbackRegistry;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;


// Generated code

import java.util.HashMap;
import java.util.Properties;


public class PistachiosServer {
	static {	 
		try {	 
			NativeUtils.loadLibraryFromJar("/libkyotocabinet.so");	 
			NativeUtils.loadLibraryFromJar("/libjkyotocabinet.so");
		} catch (Exception e) {	 
			e.printStackTrace(); // This is probably not the best way to handle exception :-)	 
		}	 
        Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    if (instance != null) 
                        instance.shutdown();
                }
            });
	}

    void shutdown() {

        manager.stop();
        BootstrapOnlineOfflineStateModel.awaitAllResetThreads();

        // close profile store
        logger.info("closing physical stores");
        profileStore.close();
        profileStore = null;
    }


	private static Logger logger = LoggerFactory.getLogger(PistachiosServer.class);
	final static MetricRegistry metrics = new MetricRegistry();
	final static JmxReporter reporter = JmxReporter.forRegistry(metrics).inDomain("pistachio.metrics").build();

	private final static Meter lookupRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "lookupRequests"));
	private final static Meter lookupFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "lookupFailureRequests"));
	private final static Meter storeRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "storeRequests"));
	private final static Meter storeFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "storeFailureRequests"));

	private final static Timer lookupTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "lookupTimer"));
	private final static Timer storeTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "storeTimer"));

	static final String PROFILE_BASE_DIR = "StorageEngine.Path";
	static final String ZOOKEEPER_SERVER = "Pistachio.ZooKeeper.Server";
	static final String PROFILE_HELIX_INSTANCE_ID = "Profile.Helix.InstanceId";

	private HelixPartitionManager<BootstrapOnlineOfflineStateModel> manager; // for partition management
	private static HelixPartitionSpectator helixPartitionSpectator;

	private TLongKyotoCabinetStore profileStore;

	private static Producer<String, byte[]> kafkaProducer = null;

    private static boolean doNothing = ConfigurationManager.getConfiguration().getBoolean("Pistachio.DoNothing", false);

    public static boolean servingAsServer() {
        return (handler != null);
    }

	public static Producer getKafkaProducerInstance() {
		try {
			if (kafkaProducer == null) {
				synchronized (logger) {
					if (kafkaProducer == null) {
						logger.debug("first time using kafka producer, creating it");
						try {
							Properties props = new Properties();
							props.put("metadata.broker.list",
									Joiner.on(",").join(ConfigurationManager.getConfiguration().getStringArray("Kafka.Broker")));
							props.put("serializer.class", "com.yahoo.ads.pb.kafka.KeyValueEncoder");
							//props.put("combiner.class", "com.yahoo.ads.pb.application.servlet.Combiner");
							//props.put("serializer.class", "StringEncoder");
							props.put("request.required.acks", ConfigurationManager.getConfiguration().getString("request.required.acks", "1"));
							props.put("queue.buffering.max.ms", ConfigurationManager.getConfiguration().getString("queue.buffering.max.ms", "3100"));
							props.put("queue.buffering.max.messages", ConfigurationManager.getConfiguration().getString("queue.buffering.max.messages", "10000"));
							props.put("producer.type", ConfigurationManager.getConfiguration().getString("producer.type", "async"));
							props.put("auto.create.topics.enable", "true");

							ProducerConfig kafkaConf = new ProducerConfig(props);

							kafkaProducer = new Producer<String, byte[]>(kafkaConf);
						} catch (Throwable t) {
							logger.error("Exception in creating Producer:", t);
						}
						logger.debug("created kafka producer");
					}
				}
			}
		} catch (Throwable t) {
			logger.error("error creating kafka producer instance", t);
		}

		return kafkaProducer;
	}



  //public static class PistachiosHandler implements Pistachios.Iface{
  public static class DefaultPistachiosHandler implements PistachiosHandler{
    Kryo kryo = new Kryo();

	String storage;

    public byte[] lookup(byte[] id, long partitionId) throws Exception
	{
		lookupRequests.mark();
		final Timer.Context context = lookupTimer.time();

		try {
            if (doNothing)
                return null;
			//return ByteBuffer.wrap(storage.getBytes());
            StorePartition storePartition = PistachiosServer.storePartitionMap.get(partitionId);

            if (storePartition == null) {
                logger.info("error getting storePartition for partition id {}, dump map: {}.", partitionId, Joiner.on(',').withKeyValueSeparator("=").join(PistachiosServer.storePartitionMap));
                throw new Exception("dont find the store partition obj");
            }
			KeyValue toRetrun = storePartition.getFromWriteCache(id);
			if (toRetrun != null) {
                logger.debug("null from cache");
				return toRetrun.value;
            }

            byte[] toRet = PistachiosServer.getInstance().getProfileStore().get(id, (int)partitionId);
			if (null != toRet) {
                Input input = new Input(toRet);

                ValueOffset valueOffset = kryo.readObject(input, ValueOffset.class);
                input.close();
                logger.debug("got from store engine: {} parsed as {}", toRet, valueOffset);
                return valueOffset.value;
            }
            logger.info("dont find value from store");
            return null;
		} catch (Exception e) {
			logger.info("Exception lookup {}", DefaultDataInterpreter.getDataInterpreter().interpretId(id), e);
			lookupFailureRequests.mark();
            throw e;
		} finally {
			context.stop();
		}
	}

    public boolean processBatch(byte[] id, long partitionId, List<byte[]> events) {
        if (doNothing)
            return true;
        if (ProcessorRegistry.getInstance().getProcessor() != null) {
            ProcessorRegistry.getInstance().getProcessor().processBatch(id, partitionId, events);
        }
        return true;
    }
    public boolean store(byte[] id, long partitionId, byte[] value, boolean callback)
	{
		storeRequests.mark();
		final Timer.Context context = storeTimer.time();

		try {
            if (doNothing)
                return true;
			long nextSeqId = -1;
            StorePartition storePartition = PistachiosServer.storePartitionMap.get(partitionId);

            if (storePartition == null) {
                logger.info("error getting storePartition for partition id {}.", partitionId);
                return false;
            }
			if ((nextSeqId = storePartition.getNextSeqId()) == -1) {
				return false;
			}

			String partitionTopic = ConfigurationManager.getConfiguration().getString("Profile.Kafka.TopicPrefix") + partitionId;
			KeyValue kv = new KeyValue();
			kv.key = id;
			kv.seqId = nextSeqId;
			kv.value = value;
            kv.callback = callback;

            long lockKey = (id.hashCode() * 7 + 11) % 1024;
            lockKey = lockKey >= 0 ? lockKey : lockKey + 1024;

            if (kv.callback && StoreCallbackRegistry.getInstance().getStoreCallback().needCallback()) {
                synchronized(storePartition.getKeyLock((int)lockKey)) {

                    logger.debug("sent msg {} {} {}, partition current seqid {}", 
                        kv.key, kv.value, kv.seqId, 
                        storePartition.getSeqId());

                    byte[] currentValue = (storePartition.getFromWriteCache(id) != null) ? storePartition.getFromWriteCache(id).value : null;
                    kv.value = StoreCallbackRegistry.getInstance().getStoreCallback().onStore(id, currentValue, value);

                    if (kv.value != null) {
                        PistachiosServer.storePartitionMap.get(partitionId).getWriteCache().putIfAbsent(new ByteArrayWrapper(id, id.length), kv);
                    }
                    KeyedMessage<String, KeyValue> message = new KeyedMessage<String, KeyValue>(partitionTopic, kv);
                    getKafkaProducerInstance().send(message);
                }
            } else {

                    logger.debug("sent msg {} {} {}, partition current seqid {}", 
                        kv.key, kv.value, kv.seqId, PistachiosServer.storePartitionMap.get(partitionId).getSeqId());

                    PistachiosServer.storePartitionMap.get(partitionId).getWriteCache().put(new ByteArrayWrapper(id, id.length), kv);

                    KeyedMessage<String, KeyValue> message = new KeyedMessage<String, KeyValue>(partitionTopic, kv);
                    getKafkaProducerInstance().send(message);
            }

				logger.debug("waiting for change to catch up {} {} within gap 20000000", PistachiosServer.storePartitionMap.get(partitionId).getSeqId() , kv.seqId);
			while(kv.seqId - PistachiosServer.storePartitionMap.get(partitionId).getSeqId() > 20000000) {
				logger.debug("waiting for change to catch up {} {} within gap 20000000", PistachiosServer.storePartitionMap.get(partitionId).getSeqId() , kv.seqId);
				Thread.sleep(30);
			}
			return true;

			//PistachiosServer.getInstance().getProfileStore().store(id, value.array());
		} catch (Exception e) {
			logger.info("error storing {} {}", 
                DefaultDataInterpreter.getDataInterpreter().interpretId(id), 
                DefaultDataInterpreter.getDataInterpreter().interpretData(value), e);
			storeFailureRequests.mark();
			return false;
		} finally {
			context.stop();
		}
	}
  }

  public static PistachiosHandler handler = null;

  public static Pistachios.Processor processor;

  private static PistachiosServer instance;

  private static HelixManager helixManager;

  private static GenericHelixController controller;// = new GenericHelixController();

  public static ConcurrentHashMap<Long, StorePartition> storePartitionMap = new ConcurrentHashMap<Long, StorePartition>();

  public static PistachiosServer getInstance() {
	  return instance;
  }
						

  public static void main(String [] args) {
    try {
		reporter.start();

	  // embed helix controller
		Configuration conf = ConfigurationManager.getConfiguration();
		logger.info("zk conn str {}", conf.getString(ZOOKEEPER_SERVER));
		helixManager = HelixManagerFactory.getZKHelixManager("PistachiosCluster",
				InetAddress.getLocalHost().getHostName(), //conf.getString(PROFILE_HELIX_INSTANCE_ID),
				InstanceType.CONTROLLER,
				conf.getString(ZOOKEEPER_SERVER));
		helixManager.connect();
		controller = new GenericHelixController();
		helixManager.addConfigChangeListener(controller);
		helixManager.addLiveInstanceChangeListener(controller);
		helixManager.addIdealStateChangeListener(controller);
		helixManager.addExternalViewChangeListener(controller);
		helixManager.addControllerListener(controller);


	  instance = new PistachiosServer();
	  instance.init();
      handler = new DefaultPistachiosHandler();
      //processor = new Pistachios.Processor(handler);

      Runnable simple = new Runnable() {
        public void run() {
          //simple(processor);
          NettyPistachioServer.startServer(handler);
        }
      };

      new Thread(simple).start();
    } catch (Exception x) {
      x.printStackTrace();
    }
  }

  public static void simple(Pistachios.Processor processor) {
    try {
      TServerTransport serverTransport = new TServerSocket(9090);
      //TServer server = new TSimpleServer(new Args(serverTransport).processor(processor));

      // Use this for a multithreaded server
	   TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport);
	   args.processor(processor);
	   args.minWorkerThreads = 50;
	   args.maxWorkerThreads = 200;
	   args.stopTimeoutUnit = TimeUnit.SECONDS;
	   args.stopTimeoutVal = 60;
       TServer server = new TThreadPoolServer(args);

      System.out.println("Starting the simple server...");
      server.serve();
    } catch (Exception e) {
		logger.info("error ", e);
      e.printStackTrace();
    }
  }

  public TLongKyotoCabinetStore getProfileStore() {
	  return profileStore;
  }

  byte[] getUserProfileLocally(byte[] userId) {
	  if (profileStore != null) {
		  return profileStore.get(userId, 0);
	  }

	  return null;
  }
	public boolean init() {
		boolean initialized = false;

		logger.info("Initializing profile server...........");
		logger.info("do nothing setting {}", doNothing);
		try {
			// open profile store
			Configuration conf = ConfigurationManager.getConfiguration();
			profileStore = new TLongKyotoCabinetStore(
			        conf.getString(PROFILE_BASE_DIR),
			        0, 8,
			        conf.getInt("StorageEngine.KC.RecordsPerPartition"),
			        conf.getLong("StorageEngine.KC.MemoryPerPartition"));
            ProcessorRegistry.getInstance().init();
            logger.info("creating helix partition sepctator {} {} {}", conf.getString(ZOOKEEPER_SERVER, "EMPTY"),
                    "PistachiosCluster", conf.getString(PROFILE_HELIX_INSTANCE_ID, "EMPTY"));
            helixPartitionSpectator = new HelixPartitionSpectator(
                    conf.getString(ZOOKEEPER_SERVER), // zkAddr
                    "PistachiosCluster",
                    InetAddress.getLocalHost().getHostName() //conf.getString(PROFILE_HELIX_INSTANCE_ID) // instanceName
                    );
            // Partition Manager for line spending
            manager = new HelixPartitionManager<>(
                    conf.getString(ZOOKEEPER_SERVER), // zkAddr
                    "PistachiosCluster",
                    InetAddress.getLocalHost().getHostName() //conf.getString(PROFILE_HELIX_INSTANCE_ID) // instanceName
                    );
            //manager.start("BootstrapOnlineOffline", new BootstrapOnlineOfflineStateModelFactory(new StorePartitionHandlerFactory()));
            manager.start("MasterSlave", new BootstrapOnlineOfflineStateModelFactory(new StorePartitionHandlerFactory()));
            //			}

			initialized = true;
		} catch (Exception e) {
			logger.error("Failed to initialize ProfileServerModule", e);
		}
		logger.info("Finished initializing profile server...........");

		return initialized;
	}

		class StorePartitionHandlerFactory implements PartitionHandlerFactory {

			public PartitionHandler createParitionHandler(int partitionId) {
				StorePartition sp = new StorePartition(partitionId);
				sp.setStoreFactory(new TKStoreFactory());

				PistachiosServer.storePartitionMap.put((long)partitionId, sp);
                logger.info("creating partition handler........... {} for partition {}", sp, partitionId);
				return sp;
			}
		}


}
