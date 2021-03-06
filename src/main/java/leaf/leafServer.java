package leaf;

/**
 * Created by weizhenyi on 2017/6/26.
 */
import java.io.File;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import Config.Config;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.*;
import zookeeper.DistributedClusterStat;
import rpc.rpcServer;
import rpc.rpcClient;

public class leafServer {
	private static final Logger LOG = LoggerFactory.getLogger(leafServer.class);
	private DistributedClusterStat zkClient;
	private final Map conf ;
	private String serverNodePath = null;
	private AtomicBoolean active = new AtomicBoolean(false);
	private volatile int increment = 0;
	private int incrementThreshHold = 0x00000FFF - 2 ;
	private volatile Integer numberServerId = null;
	private volatile long lastTimeMs = 0L;



	private  Integer serverNumberid( )
	{
		if (numberServerId == null)
		{
			if( !serverNodePath.contains("-") )
			{
				LOG.error("can not extract number id from " + serverNodePath);
				stop();
				Utils.halt_process(-1,"can not extract number id from " + serverNodePath );
			}
			int index  = serverNodePath.lastIndexOf("-");
			try {
				numberServerId = Integer.valueOf(serverNodePath.substring(index + 1));
			} catch (Exception e)
			{
				LOG.error("can not convert numberServerId from " + serverNodePath.substring(index + 1) ,e);
				stop();
				Utils.halt_process(-1,"can not extract number id from " + serverNodePath );
			}

		}
		return numberServerId;
	}
	// not so efficient,// FIXME: 2017/6/29
	public synchronized String makeFinalId()
	{
		long id = 0L;
		long base = 0x7FFFFFFFFFFFFFFFL;
		long currentTimeMs = Utils.currentTimeMs();
		if ( lastTimeMs == currentTimeMs )
		{
			if (increment >= incrementThreshHold)
			{
				increment = 0;
				Utils.sleepMs(1L);
			}
		}
		else if (lastTimeMs > currentTimeMs)
		{
			long offset = lastTimeMs - currentTimeMs;
			if (offset <= 5) //时间偏差小于5ms，则等待两倍时间
			{
				Utils.sleepMs(offset << 1);
				currentTimeMs = Utils.currentTimeMs();
				if (currentTimeMs < lastTimeMs) //还是小于 抛出异常并上报
				{
					LOG.error("the system clock was backed about : "  + offset + "ms, and can not recover with retry, stop service! ");
					stop();
					Utils.halt_process(-1,"the system clock was backed about : "  + offset + "ms, and can not recover with retry, stop service! ");
				}
				else if (currentTimeMs == lastTimeMs)//如果等待之后时间戳等于上一次ID的时间戳
				{
					if(increment >= incrementThreshHold) //时间戳用完了,重新等待1ms
					{
						increment = 0;
						Utils.sleepMs(1L);
					}
				}
				else //currentTimeMs > lastTimeMs 等待之后时间戳反而超过了上一次ID的时间戳
				{
					increment = 0;
				}
			}
			else
			{
				LOG.error("the system clock was backed about : "  + offset + "ms, stop service! ");
				stop();
				Utils.halt_process(-1,"the system clock was backed about : "  + offset + "ms, stop service! ");
			}

		}
		else //currentTimeMs > lastTimeMs
		{
			increment = 0;
		}
		currentTimeMs = Utils.currentTimeMs();
		if (currentTimeMs > lastTimeMs)
		{
			increment = 0;
		}
		long timeStamp = (currentTimeMs  & 0x0001FFFFFFFFFFL) << 22 ;
		timeStamp = base & timeStamp;
		id += timeStamp;
		long serverNumberId = (long)serverNumberid();
		id += (serverNumberId << 12);
		int incr = increment++;
		id += (long)incr;
		lastTimeMs = currentTimeMs;
		return Long.valueOf(id).toString();
	}


	private void startHeartBeatThread()
	{
		Thread heartBeat = new Thread(new Runnable() {
			private  final int interval = (Integer) conf.get(Config.LEAF_HEARTBEAT_INTERVAL);
			@Override
			public void run() {

				while(active.get() == true)
				{
					if(  serverNodePath != null )
					{
						if (zkClient != null)
						{
							try {
								zkClient.set_data(serverNodePath,Utils.barr(Utils.currentTimeMs()));
							} catch (Exception e)
							{
								LOG.error("Faild to set heartBeat timestamp for path: " + serverNodePath);
							}
						}
					}
					Utils.sleepMs(interval);
				}
			}
		});
		heartBeat.setName("leaf heartbeat");
		heartBeat.setDaemon(true);
		heartBeat.start();
	}


	public leafServer(final Map conf )
	{
		this.conf = conf;
		try {
			zkClient = new DistributedClusterStat(conf);
		} catch (Exception e)
		{
			stop();
			Utils.halt_process(-1,e.getMessage());
		}
	}
	public Map getServerConf()
	{
		return conf;
	}


	public void setServerNodePath(String serverNodePath )
	{
		this.serverNodePath = serverNodePath;
		active.set(true);
	}
	public String getServerNodePath()
	{
		return serverNodePath;
	}

	private void stop()
	{
		active.set(false);
		if (zkClient != null)
		{
			zkClient.close();
			zkClient = null;
		}
		Utils.sleepMs(5000);
	}

	private boolean isMatchPeersTimeStamps(Map conf) throws Exception
	{
		List<String> peersHostPort = zkClient.get_childern(String.valueOf(conf.get(Config.LEAF_ZOOKEEPER_EPHEMERAL)),true);
		Map<String,Long> timestamps = rpcClient.getPeersTimeStamps(peersHostPort);
		//删除自身的时间戳信息
		timestamps.remove(Utils.getIPPort(conf));
		timestamps.remove(Utils.getInternetIPPort(conf));

		if(timestamps.isEmpty())//只有自己的机器本身
			return true;
		BigInteger total = BigInteger.ZERO;
		for(Long v : timestamps.values())
		{
			total = total.add(BigInteger.valueOf(v));
		}
		long average = total.divide(BigInteger.valueOf(timestamps.size())).longValue();
		if (Math.abs(average - Utils.currentTimeMs()) > (Integer)conf.get(Config.LEAF_AVERAGE_TIMESTAMP_THRESHOLD))
		{
			LOG.error("current server timestamp :" + Utils.formatTimeStampMs(Utils.currentTimeMs())
					+ " does not match the peers average timestamp : "
					+ Utils.formatTimeStampMs(average) + " ,exit jvm.");
			return false;
		}
		return true;
	}

	private void launchServer(final Map conf) throws Exception
	{
		String localDir = PathUtils.leafLocalDir(conf);
		LOG.info("Current localDir: " + localDir);
		String pidPath = Utils.createPid(conf);
		LOG.info("Current leafServer pidPath: " + pidPath);
		FileUtils.forceMkdir(new File(conf.get(Config.LEAF_HOME) + Config.FILE_SEPERATEOR + "logs"));
		LOG.info("Current logDir :" + conf.get(Config.LEAF_HOME) + Config.FILE_SEPERATEOR + "logs");

		FileUtils.forceMkdir(new File(Utils.localDataPath(conf)));

		String dataDir = Utils.localDataPath(conf);
		LOG.info("Current dataDir :" + dataDir);

		zkClient.mkdirs(String.valueOf(conf.get(Config.LEAF_ZOOKEEPER_FOREVER)));
		zkClient.mkdirs(String.valueOf(conf.get(Config.LEAF_ZOOKEEPER_EPHEMERAL)));

		boolean match = isMatchPeersTimeStamps(conf);
		if( !match )
		{
			stop();
			throw new RuntimeException("current server timestamp "
					+ " does not match the peers average timestamp");
		}

		//设置临时节点
		zkClient.set_ephemeral_node(Utils.getEphemeralNodePath(conf),"".getBytes());


		List<String> serverid = PathUtils.read_dir_contents(dataDir);
		if (serverid.isEmpty()) //如果当前的 data目录下面没有 serverid文件，说明是第一次向zk注册
		{
			String serverNodePathPrefix = Utils.serverNodePathPrefix(conf);
			String serverNodePath = makeServerNode(serverNodePathPrefix,Utils.barr(new Long(Utils.currentTimeMs())));
			if (serverNodePath == null || serverNodePath.isEmpty())
			{
				LOG.error("Failed to make serverNodePath , exit from the jvm");
				stop();
				Utils.halt_process(-1,"Failed to make serverNodePath , exit from the jvm");
			}
			String[] pathTokens = serverNodePath.split("/");
			if (  pathTokens.length > 0
					&& pathTokens[pathTokens.length -1].contains("-")
					&& pathTokens[pathTokens.length -1].contains(":"))
			{
				LOG.info("The serverNodePath : " + serverNodePath);
				PathUtils.touch(dataDir + Config.FILE_SEPERATEOR + pathTokens[pathTokens.length -1 ]);
				this.setServerNodePath(serverNodePath);
			}
			else
			{
				LOG.error("Got the wrong format serverNodePath : " + serverNodePath);
				stop();
				Utils.halt_process(-1,"Got the wrong format serverNodePath , exit from the jvm");
			}
		}
		else
		{
			if ( serverid.size() > 1 )
			{
				LOG.error("Got more than 1 serverid files,exit from the jvm." );
				stop();
				Utils.halt_process(-1,"Got more than 1 serverid files,exit from the jvm.");
			}
			else
			{
				String serverId = serverid.get(0);
				String zkServerNode = Utils.serverNodePath(conf,serverId);
				byte [] timeStampBytes = zkClient.get_data(zkServerNode,true);
				if (timeStampBytes == null || timeStampBytes.length != 8)
				{
					LOG.error("Got no data from node ,exit from the jvm.: " + zkServerNode);
					stop();
					Utils.halt_process(-1,"Got no data from node,exit from the jvm.");
				}
				else
				{
					long timeStampMs = Utils.bytesTolong(timeStampBytes);
					LOG.info("The last register time is :" + Utils.formatTimeStampMs(timeStampMs));
					if (timeStampMs >= Utils.currentTimeMs())
					{
						LOG.error("the server clock has been backed , exit from the jvm.");
						Utils.halt_process(-1,"the server clock has been backed , exit from the jvm.");
					}
					else
					{
						Long currentMs = Utils.currentTimeMs();
						this.setServerNodePath(zkServerNode);
						zkClient.set_data(zkServerNode,Utils.barr(currentMs));
						LOG.info("time matched,last register time : " + Utils.formatTimeStampMs(timeStampMs)
						           + "current register time : " + Utils.formatTimeStampMs(currentMs));
					}

				}

			}

		}

		startHeartBeatThread();
	}

	private void addShutdownHook()
	{
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				leafServer.this.stop();
				LOG.info("finally stop the leafServer......");
			}
		}));
	}

	private String makeServerNode( String path, byte[] data)
	{
		try {
			return zkClient.set_sequentail_node_data(path,data);
		} catch (Exception e)
		{
			LOG.error("Failed to make server node: " + path ,e);
			stop();
			Utils.halt_process(-1,"Failed to make server node: " + "path");
		}
		return null;
	}
	public void startRPC() throws Exception
	{
		rpcServer.startRPCServer(this ,Utils.getInternetIp(),(Integer)conf.get(Config.LEAF_RPC_PORT));
	}
	public static void main(String[] args) throws Exception
	{
		String leaf_home = System.getProperty(Config.LEAF_HOME);
		Map conf = Utils.readLeafConfig();
		if (leaf_home != null)
		{
			conf.put(Config.LEAF_HOME ,leaf_home);
		}
		LOG.info("the server configurations : " + conf.toString());
		leafServer server = new leafServer(conf);
		try {
			server.addShutdownHook();
			server.launchServer(conf);
			server.startRPC();
		}
		catch (Exception e)
		{
			LOG.error("Failed to start leaf server,exit",e);
			server.stop();
		}



	}
}
