/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.core.rpc.netty.v1;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.seata.core.serializer.Serializer;
import io.seata.core.compressor.Compressor;
import io.seata.core.compressor.CompressorFactory;
import io.seata.core.protocol.ProtocolConstants;
import io.seata.core.protocol.RpcMessage;
import io.seata.core.serializer.SerializerServiceLoader;
import io.seata.core.serializer.SerializerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * <pre>
 * 0     1     2     3     4     5     6     7     8     9    10     11    12    13    14    15    16
 * +-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+-----+
 * |   magic   |Proto|     Full length       |    Head   | Msg |Seria|Compr|     RequestId         |
 * |   code    |colVer|    (head+body)      |   Length  |Type |lizer|ess  |                       |
 * +-----------+-----------+-----------+-----------+-----------+-----------+-----------+-----------+
 * |                                                                                               |
 * |                                   Head Map [Optional]                                         |
 * +-----------+-----------+-----------+-----------+-----------+-----------+-----------+-----------+
 * |                                                                                               |
 * |                                         body                                                  |
 * |                                                                                               |
 * |                                        ... ...                                                |
 * +-----------------------------------------------------------------------------------------------+
 * </pre>
 * <p>
 * <li>Full Length: include all data </li>
 * <li>Head Length: include head data from magic code to head map. </li>
 * <li>Body Length: Full Length - Head Length</li>
 * </p>
 * https://github.com/seata/seata/issues/893
 *
 * @author Geng Zhang
 * @see ProtocolV1Decoder
 * @since 0.7.0
 */
public class ProtocolV1Encoder extends MessageToByteEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolV1Encoder.class);

    @Override
    public void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
        try {
            if (msg instanceof RpcMessage) {
                RpcMessage rpcMessage = (RpcMessage) msg;

                // 完整的消息长度
                int fullLength = ProtocolConstants.V1_HEAD_LENGTH;
                // 消息头长度
                int headLength = ProtocolConstants.V1_HEAD_LENGTH;

                // 获取到消息的类型，返回一个byte类型，占用一个字节
                byte messageType = rpcMessage.getMessageType();
                // 先写入指定字节数量的Magic Number（魔数），代表一个网络消息的开始
                out.writeBytes(ProtocolConstants.MAGIC_CODE_BYTES);
                // 写入消息的版本号
                out.writeByte(ProtocolConstants.VERSION);
                // full Length(4B) and head length(2B) will fix in the end.
                // 标记index位置，当前那些写入的字节数+6，让write index跳过6个字节，即空出6个字节位置，再继续往后写
                out.writerIndex(out.writerIndex() + 6);
                // 在版本号之后的6个字节后，继续写消息的类型、codec、compressor（都一个字节）
                out.writeByte(messageType);
                out.writeByte(rpcMessage.getCodec());
                out.writeByte(rpcMessage.getCompressor());
                // 写入4个字节消息的id
                out.writeInt(rpcMessage.getId());

                // direct write head with zero-copy
                // 使用零拷贝写入消息头
                Map<String, String> headMap = rpcMessage.getHeadMap();
                if (headMap != null && !headMap.isEmpty()) {
                    // 真正写入消息头：把消息头进行编码，然后转换为字节数组写入到out里；
                    // 消息头写完之后可以获取到消息头的长度
                    int headMapBytesLength = HeadMapSerializer.getInstance().encode(headMap, out);
                    headLength += headMapBytesLength;
                    // 消息长度累加
                    fullLength += headMapBytesLength;
                }

                byte[] bodyBytes = null;
                // 根据消息类型对消息体进行序列化
                if (messageType != ProtocolConstants.MSGTYPE_HEARTBEAT_REQUEST
                        && messageType != ProtocolConstants.MSGTYPE_HEARTBEAT_RESPONSE) {
                    // heartbeat has no body
                    // 根据Codec获取序列化组件，默认是SeataSerializer
                    Serializer serializer = SerializerServiceLoader.load(SerializerType.getByCode(rpcMessage.getCodec()));
                    // 序列化
                    bodyBytes = serializer.serialize(rpcMessage.getBody());
                    // 根据Compressor获取压缩组件
                    Compressor compressor = CompressorFactory.getCompressor(rpcMessage.getCompressor());
                    bodyBytes = compressor.compress(bodyBytes);
                    // 消息长度累加
                    fullLength += bodyBytes.length;
                }

                // 写入消息体
                if (bodyBytes != null) {
                    out.writeBytes(bodyBytes);
                }

                // fix fullLength and headLength
                int writeIndex = out.writerIndex();
                // skip magic code(2B) + version(1B)
                out.writerIndex(writeIndex - fullLength + 3);
                out.writeInt(fullLength);
                out.writeShort(headLength);
                out.writerIndex(writeIndex);
            } else {
                throw new UnsupportedOperationException("Not support this class:" + msg.getClass());
            }
        } catch (Throwable e) {
            LOGGER.error("Encode request error!", e);
        }
    }
}
