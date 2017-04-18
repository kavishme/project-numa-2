package gash.router.server.election;

import gash.router.server.ServerState;
import java.util.Timer;
import java.util.concurrent.ThreadLocalRandom;
import gash.router.server.edges.EdgeMonitor;
import gash.router.server.edges.EdgeList;
import gash.router.server.edges.EdgeInfo;


import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import gash.router.server.WorkInit;

import pipe.common.Common.Header;
import pipe.voteRequest.VoteRequest.VoteReq;
import pipe.appendEntries.AppendEntries.AppendEntry;
import pipe.work.Work.WorkMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.PrintWriter;
import java.io.File;

import gash.router.container.RoutingConf.RoutingEntry;



public class Leader implements Runnable{
	protected static Logger logger = LoggerFactory.getLogger("leader");

	private boolean isLeader=false;
	private ServerState state;
	private int leaderId=0;
	private int currentTerm;
	private int lastAppliedIndex;
	private int lastCommitIndex;

	private EdgeList outboundEdges;
	private EdgeList inboundEdges;
	private long dt = 2000;
	private boolean forever = true;

	public Leader(ServerState state){
		this.state=state;
		this.isLeader=state.getStatus().getLeader();
		this.leaderId=state.getStatus().getLeaderId();

		System.out.println("leader true or not::  "+isLeader);

		if (state == null)
			throw new RuntimeException("state is null");

		this.outboundEdges = new EdgeList();
		this.inboundEdges = new EdgeList();
		
		state.getStatus().setTotalVotesRecievedForThisTerm(0);

		if (state.getConf().getRouting() != null) {
			for (RoutingEntry e : state.getConf().getRouting()) {
				this.outboundEdges.addNode(e.getId(), e.getHost(), e.getPort());
			}
		}

		// cannot go below 2 sec
		if (state.getConf().getHeartbeatDt() > this.dt)
			this.dt = state.getConf().getHeartbeatDt();
	}

	@Override
	public void run() {
		while (this.isLeader && this.leaderId==state.getConf().getNodeId()) {
			try {
				System.out.println("append etnries in leader run method");
				sendAppendEntries();
				Thread.sleep(dt);

			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public void sendAppendEntries(){
		System.out.println("inside sendAppendEntries method");

		if(isLeader){
			System.out.println("inside sendAppendEntries method:: isleader true");
			this.currentTerm=state.getStatus().getCurrentTerm();
			state.getStatus().setTotalAppendEntrySuccessForThisTerm(0);

			EdgeMonitor em = new EdgeMonitor(state);
			//this.outboundEdges= em.getOutboundEdges();


			for (EdgeInfo ei : this.outboundEdges.getMap().values()) {
				if (ei.getChannel() != null && ei.isActive()) {
					//ei.retry = 0;
					WorkMessage wm = createAppendEntryRequest();
					ei.getChannel().writeAndFlush(wm);
				} else {
					try {
						EventLoopGroup group = new NioEventLoopGroup();
						WorkInit si = new WorkInit(state, false);
						Bootstrap b = new Bootstrap();
						b.group(group).channel(NioSocketChannel.class).handler(si);
						b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
						b.option(ChannelOption.TCP_NODELAY, true);
						b.option(ChannelOption.SO_KEEPALIVE, true);

						ChannelFuture channel = b.connect(ei.getHost(), ei.getPort()).syncUninterruptibly();

						ei.setChannel(channel.channel());
						ei.setActive(channel.channel().isActive());
					} catch (Exception e) {
						logger.error("error in conecting to node " + ei.getRef() + " exception " + e.getMessage());
					}
				}
			}
		}
	}


	public WorkMessage createAppendEntryRequest(){

		//update own entry first and then send appendEntry message to all nodes in the network

		// create an entry first

		Header.Builder hb = Header.newBuilder();
		hb.setNodeId(state.getConf().getNodeId());
		hb.setDestination(-1);
		hb.setTime(System.currentTimeMillis());

		AppendEntry.Builder ab = AppendEntry.newBuilder();
		ab.setTerm(this.currentTerm);
		ab.setLeaderId(this.leaderId);
		ab.setPrevLogIndex(state.getStatus().getLastAplliedIndex());
		ab.setPrevLogTerm(state.getStatus().getLastTermInLog());

		String[] entry = new String[4];
		entry[0]=Integer.toString(currentTerm);
		entry[1]=Integer.toString(leaderId);
		entry[2]=Integer.toString(state.getStatus().getLastAplliedIndex()+1);
		entry[3]="AppendEntry every heartbeat timeout";
		//entries should contain term,leaderid,index,message

		ab.setEntries(0,Integer.toString(currentTerm));
		ab.setEntries(1,Integer.toString(leaderId));
		ab.setEntries(2,Integer.toString(state.getStatus().getLastAplliedIndex()+1));
		ab.setEntries(3,"AppendEntry every heartbeat timeout");

		ab.setLeaderCommit(state.getStatus().getCommitIndex());

		WorkMessage.Builder wm = WorkMessage.newBuilder();
		wm.setHeader(hb);
		wm.setAeMsg(ab);
		wm.setSecret(121316550);

		// write entry object to leader node itself first

		try{

			PrintWriter pw=new PrintWriter(new File(state.getDbPath()+"/appendEntryLog.csv"));
			StringBuilder sb= new StringBuilder();

			for(int i=0;i<entry.length;i++){
				sb.append(entry[i]);
				sb.append(",");		
			}
			sb.append("\n");

			pw.write(sb.toString());
			
			pw.close();
			System.out.println("Leader wrote the log to its own fiel in leader class");

		}
		catch(Exception e){
			logger.error("error in writing AppendEntry to leader node", e);
		}

		return wm.build();
	}

	//TODO update lastappleid index on success
	//Add entry to own file
}