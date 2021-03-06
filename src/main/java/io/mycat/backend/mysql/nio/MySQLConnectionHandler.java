/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.backend.mysql.nio;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger; import org.slf4j.LoggerFactory;

import io.mycat.backend.mysql.ByteUtil;
import io.mycat.backend.mysql.nio.handler.LoadDataResponseHandler;
import io.mycat.backend.mysql.nio.handler.ResponseHandler;
import io.mycat.net.handler.BackendAsyncHandler;
import io.mycat.net.mysql.EOFPacket;
import io.mycat.net.mysql.ErrorPacket;
import io.mycat.net.mysql.OkPacket;
import io.mycat.net.mysql.RequestFilePacket;

/**
 * life cycle: from connection establish to close <br/>
 * 
 * @author mycat
 */
public class MySQLConnectionHandler extends BackendAsyncHandler {
	private static final Logger logger = LoggerFactory
			.getLogger(MySQLConnectionHandler.class);
	private static final int RESULT_STATUS_INIT = 0;
	private static final int RESULT_STATUS_HEADER = 1;
	private static final int RESULT_STATUS_FIELD_EOF = 2;

	private final MySQLConnection source;
	private volatile int resultStatus;
	private volatile byte[] header;
	private volatile List<byte[]> fields;

	/**
	 * life cycle: one SQL execution
	 */
	private volatile ResponseHandler responseHandler;

	public MySQLConnectionHandler(MySQLConnection source) {
		this.source = source;
		this.resultStatus = RESULT_STATUS_INIT;
	}

	public void connectionError(Throwable e) {
		if (responseHandler != null) {
			responseHandler.connectionError(e, source);
		}

	}

	public MySQLConnection getSource() {
		return source;
	}

	@Override
	public void handle(byte[] data) {
		offerData(data, source.getProcessor().getExecutor());
	}

	@Override
	protected void offerDataError() {
		resultStatus = RESULT_STATUS_INIT;
		throw new RuntimeException("offer data error!");
	}

	/**
	 * MySQL服务端响应客户端查询请求的流程可以分为三个阶段：
	 （第一阶段）客户端发送查询请求包COM_QUERY （command query packet），如果有结果集返回，且结果集不为空，则返回FieldCount（列数量）包；
	 		如果结果集为空，则返回OKPacket；如果命令有错，则返回ERRPacket；如果是Load file data命令，则返回LOCAL_INFILE_Request。
	 （第二阶段）如果有结果集返回，则先返回列集合，所有列返回完了之后，会返回EOFPacket；如果过程中出现错误，则返回错误包。
	 （第三阶段）之后返回行记录，返回全部行记录之后，返回EOFPacket。如果有错误，回错误包。
	 */
	@Override
	protected void handleData(byte[] data) {
		switch (resultStatus) {
		// 第一阶段
		case RESULT_STATUS_INIT:
			switch (data[4]) {
			// 返回OKPacket
			case OkPacket.FIELD_COUNT:
				handleOkPacket(data);
				break;
			// 返回错误包
			case ErrorPacket.FIELD_COUNT:
				handleErrorPacket(data);
				break;
			// 返回Load Data进一步操作
			case RequestFilePacket.FIELD_COUNT:
				handleRequestPacket(data);
				break;
			// 返回结果集列数量
			default:
				// 记录列数量并进入第二阶段
				resultStatus = RESULT_STATUS_HEADER;
				header = data;
				fields = new ArrayList<byte[]>((int) ByteUtil.readLength(data,
						4));
			}
			break;
		// 第二阶段
		case RESULT_STATUS_HEADER:
			switch (data[4]) {
			// 返回错误包
			case ErrorPacket.FIELD_COUNT:
				resultStatus = RESULT_STATUS_INIT;
				handleErrorPacket(data);
				break;
			// 返回EOF，证明列集合返回完毕，进入第三阶段
			case EOFPacket.FIELD_COUNT:
				resultStatus = RESULT_STATUS_FIELD_EOF;
				handleFieldEofPacket(data);
				break;
			// 返回的是列集合，记录
			default:
				fields.add(data);
			}
			break;
		// 第三阶段
		case RESULT_STATUS_FIELD_EOF:
			switch (data[4]) {
			// 返回错误包
			case ErrorPacket.FIELD_COUNT:
				resultStatus = RESULT_STATUS_INIT;
				handleErrorPacket(data);
				break;
			// 返回EOF，证明结果集返回完毕，回到第一阶段等待下一个请求的响应
			case EOFPacket.FIELD_COUNT:
				resultStatus = RESULT_STATUS_INIT;
				handleRowEofPacket(data);
				break;
			// 返回结果集包
			default:
				handleRowPacket(data);
			}
			break;
		default:
			throw new RuntimeException("unknown status!");
		}
	}

	public void setResponseHandler(ResponseHandler responseHandler) {
		// logger.info("set response handler "+responseHandler);
		// if (this.responseHandler != null && responseHandler != null) {
		// throw new RuntimeException("reset agani!");
		// }
		this.responseHandler = responseHandler;
	}

	/**
	 * OK数据包处理
	 */
	private void handleOkPacket(byte[] data) {
		ResponseHandler respHand = responseHandler;
		if (respHand != null) {
			respHand.okResponse(data, source);
		}
	}

	/**
	 * ERROR数据包处理
	 */
	private void handleErrorPacket(byte[] data) {
		ResponseHandler respHand = responseHandler;
		if (respHand != null) {
			respHand.errorResponse(data, source);
		} else {
			closeNoHandler();
		}
	}

	/**
	 * load data file 请求文件数据包处理
	 */
	private void handleRequestPacket(byte[] data) {
		ResponseHandler respHand = responseHandler;
		if (respHand != null && respHand instanceof LoadDataResponseHandler) {
			((LoadDataResponseHandler) respHand).requestDataResponse(data,
					source);
		} else {
			closeNoHandler();
		}
	}

	/**
	 * 字段数据包结束处理
	 */
	private void handleFieldEofPacket(byte[] data) {
		ResponseHandler respHand = responseHandler;
		if (respHand != null) {
			respHand.fieldEofResponse(header, fields, data, source);
		} else {
			closeNoHandler();
		}
	}

	/**
	 * 行数据包处理
	 */
	private void handleRowPacket(byte[] data) {
		ResponseHandler respHand = responseHandler;
		if (respHand != null) {
			respHand.rowResponse(data, source);
		} else {
			closeNoHandler();

		}
	}

	private void closeNoHandler() {
		if (!source.isClosedOrQuit()) {
			source.close("no handler");
			logger.warn("no handler bind in this con " + this + " client:"
					+ source);
		}
	}

	/**
	 * 行数据包结束处理
	 */
	private void handleRowEofPacket(byte[] data) {
		if (responseHandler != null) {
			responseHandler.rowEofResponse(data, source);
		} else {
			closeNoHandler();
		}
	}

}