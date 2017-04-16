/**
 * Copyright 2016 Gash.
 *
 * This file and intellectual content is protected under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package gash.router.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import pipe.common.Common.AppendLogItem;
import pipe.common.Common.Failure;
import pipe.common.Common.GetLog;
import pipe.common.Common.Log;
import pipe.common.Common.RemoveLogItem;
import pipe.common.Common.RequestAppendItem;
import pipe.common.Common.RequestRemoveItem;
import pipe.work.Work.Heartbeat;
import pipe.work.Work.Task;
import pipe.work.Work.WorkMessage;
import pipe.work.Work.WorkState;

/**
 * The message handler processes json messages that are delimited by a 'newline'
 * 
 * TODO replace println with logging!
 * 
 * @author gash
 * 
 */
public class WorkHandler extends SimpleChannelInboundHandler<WorkMessage> {
	protected static Logger logger = LoggerFactory.getLogger("work");
	protected ServerState serverState;
	protected boolean debug = true;

	public WorkHandler(ServerState serverState) {
		if (serverState != null) {
			this.serverState = serverState;
		}
	}

	/**
	 * override this method to provide processing behavior. T
	 * 
	 * @param msg
	 */

	public void handleMessage(WorkMessage msg, Channel channel) {
		if (msg == null) {
			logger.error("ERROR: Unexpected content - " + msg);
			return;
		}

		if (debug)
			PrintUtil.printWork(msg);

		// TODO How can you implement this without if-else statements?
		try {
			if (msg.hasBeat()) {
				Heartbeat hb = msg.getBeat();
				logger.info("heartbeat from " + msg.getHeader().getNodeId());
			} else if (msg.hasPing()) {
				logger.info("ping from " + msg.getHeader().getNodeId());
				boolean p = msg.getPing();
				WorkMessage.Builder rb = WorkMessage.newBuilder();
				rb.setPing(true);
				channel.write(rb.build());
			} else if (msg.hasErr()) {
				Failure err = msg.getErr();
				logger.error("failure from " + msg.getHeader().getNodeId());
				// PrintUtil.printFailure(err);
			} else if (msg.hasTask()) {
				Task t = msg.getTask();
			} else if (msg.hasState()) {
				WorkState s = msg.getState();
			} else if (msg.hasGetLog()) {
				// sender want to the log!
				// build log message from hashTable
				Log.Builder logmsg = Log.newBuilder();
				logmsg.putAllHashTable(ServerState.hashTable);
				// write log file back to sender
				channel.write(logmsg);
			} else if (msg.hasRequestAppend() && serverState.isLeader()) {
				// FOLLOWER want to append, ONLY LEADER should read this message
				RequestAppendItem request = msg.getRequestAppend();
				ServerState.hashTable.put(request.getFilename(), request.getLocationList());
				// TODO append success, notify all FOLLOWERS
				
			} else if (msg.hasRequestRemove() && serverState.isLeader()) {
				// FOLLOWER want to remove, ONLY LEADER should read this message
				RequestRemoveItem request = msg.getRequestRemove();
				ServerState.hashTable.remove(request.getFilename());
				// TODO remove success, notify all FOLLOWERS
				
			} else if (msg.hasAppend() && msg.getHeader().getNodeId() == serverState.getCurrentLeader()) {
				// only leader should send out this message, check is from
				// leader?
				// get chunk_id, and chunk_location from msg and add to
				// hashTable
				AppendLogItem item = msg.getAppend();
				ServerState.hashTable.put(item.getFilename(), item.getLocationList());
				
			} else if (msg.hasRemove() && msg.getHeader().getNodeId() == serverState.getCurrentLeader()) {
				// only leader should send out this message, check is from
				// leader?
				// get chunk_id from msg, remove the chunk_id for hashTable
				RemoveLogItem item = msg.getRemove();
				ServerState.hashTable.remove(item.getFilename());
			}
		} catch (Exception e) {
			logger.error("Exception: " + e.getMessage());
			Failure.Builder eb = Failure.newBuilder();
			eb.setId(serverState.getConf().getNodeId());
			eb.setRefId(msg.getHeader().getNodeId());
			eb.setMessage(e.getMessage());
			WorkMessage.Builder rb = WorkMessage.newBuilder(msg);
			rb.setErr(eb);
			channel.write(rb.build());
		}

		System.out.flush();

	}

	/**
	 * a message was received from the server. Here we dispatch the message to
	 * the client's thread pool to minimize the time it takes to process other
	 * messages.
	 * 
	 * @param ctx
	 *            The channel the message was received from
	 * @param msg
	 *            The message
	 */
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, WorkMessage msg) throws Exception {
		if(msg.getHeader().getDestination() == serverState.getConf().getNodeId())
			handleMessage(msg, ctx.channel());
		else
			serverState.wmforward.addLast(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		logger.error("Unexpected exception from downstream.", cause);
		ctx.close();
	}

}