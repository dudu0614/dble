package io.mycat.backend.mysql.nio.handler.transaction;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.backend.BackendConnection;
import io.mycat.backend.mysql.nio.MySQLConnection;
import io.mycat.backend.mysql.nio.handler.MultiNodeHandler;
import io.mycat.backend.mysql.nio.handler.ResponseHandler;
import io.mycat.route.RouteResultsetNode;
import io.mycat.server.NonBlockingSession;

public abstract class AbstractRollbackNodesHandler extends MultiNodeHandler implements RollbackNodesHandler {

	protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractRollbackNodesHandler.class);
	protected ResponseHandler responsehandler;

	public AbstractRollbackNodesHandler(NonBlockingSession session) {
		super(session);
		resetResponseHandler();
	}

	@Override
	public void setResponseHandler(ResponseHandler responsehandler) {
		this.responsehandler = responsehandler;
	}
	protected abstract void endPhase(MySQLConnection mysqlCon);
	protected abstract void rollbackPhase(MySQLConnection mysqlCon);

	public void rollback() {
		final int initCount = session.getTargetCount();
		lock.lock();
		try {
			reset(initCount);
		} finally {
			lock.unlock();
		}
		if (session.closed()) {
			decrementCountToZero();
			return;
		}
	
		// 执行
		int started = 0;
		for (final RouteResultsetNode node : session.getTargetKeys()) {
			if (node == null) {
				setFail("null is contained in RoutResultsetNodes, source = " + session.getSource());
				LOGGER.warn(error);
				continue;
			}
			final BackendConnection conn = session.getTarget(node);
	
			if (conn != null) {
				boolean isClosed = conn.isClosedOrQuit();
				if (isClosed) {
//					session.getSource().writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR,
//							"receive rollback,but find backend con is closed or quit");??
					setFail(conn + "receive rollback,but fond backend con is closed or quit");
					LOGGER.warn(error);
					continue;
				}
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("rollback job run for " + conn);
				}
				if (clearIfSessionClosed(session)) {
					return;
				}
				conn.setResponseHandler(responsehandler);
				MySQLConnection mysqlCon = (MySQLConnection) conn;
				if (session.getXaState() == null) {
					rollbackPhase(mysqlCon);
				} else {
					switch (session.getXaState()) {
					case TX_STARTED_STATE:
						endPhase(mysqlCon);
						break;
					case TX_ENDED_STATE:
					case TX_PREPARED_STATE:
						rollbackPhase(mysqlCon);
						break;
					default:
					}
				}
				++started;
			}
		}
	
		if (started < initCount && decrementCountBy(initCount-started)) {
			/**
			 * assumption: only caused by front-end connection close. <br/>
			 * Otherwise, packet must be returned to front-end
			 */ 
			cleanAndFeedback(error.getBytes());
		}
	}

	protected void cleanAndFeedback(byte[] data) {
		// clear all resources
		session.clearResources(false);
		if(session.closed()){
			return;
		}
		if (this.isFail()){
			createErrPkg(error).write(session.getSource());
		} else {
			session.getSource().write(data);
		}
	}
	@Override
	public void rowEofResponse(byte[] eof, BackendConnection conn) {
		LOGGER.error(new StringBuilder().append("unexpected packet for ")
				.append(conn).append(" bound by ").append(session.getSource())
				.append(": field's eof").toString());
	}

	@Override
	public void connectionAcquired(BackendConnection conn) {
		LOGGER.error("unexpected invocation: connectionAcquired from rollback");
	}

	@Override
	public void fieldEofResponse(byte[] header, List<byte[]> fields, byte[] eof, BackendConnection conn) {
		LOGGER.error(new StringBuilder().append("unexpected packet for ")
				.append(conn).append(" bound by ").append(session.getSource())
				.append(": field's eof").toString());
	}

	@Override
	public void rowResponse(byte[] row, BackendConnection conn) {
		LOGGER.error(new StringBuilder().append("unexpected packet for ")
				.append(conn).append(" bound by ").append(session.getSource())
				.append(": field's eof").toString());
	}

	@Override
	public void writeQueueAvailable() {
	
	}

}