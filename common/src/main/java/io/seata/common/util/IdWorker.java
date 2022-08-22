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
package io.seata.common.util;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author funkye
 * @author selfishlover
 */
public class IdWorker {

    /**
     * Start time cut (2020-05-03)
     */
    private final long twepoch = 1588435200000L;

    /**
     * The number of bits occupied by workerId
     */
    private final int workerIdBits = 10;

    /**
     * The number of bits occupied by timestamp
     */
    private final int timestampBits = 41;

    /**
     * The number of bits occupied by sequence
     */
    private final int sequenceBits = 12;

    /**
     * Maximum supported machine id, the result is 1023
     */
    private final int maxWorkerId = ~(-1 << workerIdBits);

    /**
     * business meaning: machine ID (0 ~ 1023)
     * actual layout in memory:
     * highest 1 bit: 0
     * middle 10 bit: workerId
     * lowest 53 bit: all 0
     */
    private long workerId;

    /**
     * 又是一个雪花算法（64位，8字节）
     * timestamp and sequence mix in one Long
     * highest 11 bit: not used
     * middle  41 bit: timestamp
     * lowest  12 bit: sequence
     */
    private AtomicLong timestampAndSequence;

    /**
     * 从一个long数组类型中抽取出一个时间戳伴随序列号，偏向一个辅助性质
     * mask that help to extract timestamp and sequence from a long
     */
    private final long timestampAndSequenceMask = ~(-1L << (timestampBits + sequenceBits));

    /**
     * instantiate an IdWorker using given workerId
     * @param workerId if null, then will auto assign one
     */
    public IdWorker(Long workerId) {
        // 初始化时间戳sequence
        initTimestampAndSequence();
        // 初始化workerId
        initWorkerId(workerId);
    }

    /**
     * init first timestamp and sequence immediately
     */
    private void initTimestampAndSequence() {
        // 拿到当前时间戳 - （2020-05-03 时间戳）的数值，即当前时间相对2020-05-03的时间戳
        long timestamp = getNewestTimestamp();
        // 把时间戳左移12位，后12位流程sequence使用
        long timestampWithSequence = timestamp << sequenceBits;
        // 把混合sequence的时间戳赋值给timestampAndSequence
        this.timestampAndSequence = new AtomicLong(timestampWithSequence);
    }

    /**
     * init workerId
     * @param workerId if null, then auto generate one
     */
    private void initWorkerId(Long workerId) {
        if (workerId == null) {
            // workid为null时，自动生成一个workerId
            workerId = generateWorkerId();
        }
        // workerId最大只能是1023，因为其只占10bit
        if (workerId > maxWorkerId || workerId < 0) {
            String message = String.format("worker Id can't be greater than %d or less than 0", maxWorkerId);
            throw new IllegalArgumentException(message);
        }
        this.workerId = workerId << (timestampBits + sequenceBits);
    }

    /**
     * 通过snowflake雪花算法生成transactionId
     * 一共64位(8字节)
     * get next UUID(base on snowflake algorithm), which look like:
     * highest 1 bit: always 0 1个bit始终是0
     * next   10 bit: workerId  10个bit表示机器号
     * next   41 bit: timestamp 41个bit表示当前机器的时间戳（ms级别），每毫秒递增
     * lowest 12 bit: sequence 12位的序号，如果一台机器在一毫秒内有很多线程要来生成id，12bit的sequence会自增
     * @return UUID
     */
    public long nextId() {
        // 解决sequence序列号被用尽问题
        waitIfNecessary();
        // 自增的时间戳的sequence，等于对一个毫秒内的sequence做累加操作
        //    1.如果大量的线程并发获取UUID，可能导致一个毫秒内的sequence耗尽，进而导致时间戳也累加了，不过当前实际时间实际还是在那一毫秒
        //    2.而AtomicLong类型已经加到了下一个/几个毫秒，尽可能的保证了QPS过大后的UUID生成算法稳定性
        long next = timestampAndSequence.incrementAndGet();
        // 把最新时间戳（包括序列号）和mask做一个与运算，得到真正的时间戳伴随的序列
        long timestampWithSequence = next & timestampAndSequenceMask;
        // 最后和workerId做或运算，得到最终的UUID；
        return workerId | timestampWithSequence;
    }

    /**
     * 阻塞当前线程，如果UUID的QPS过高导致时间戳对应的sequence序号被耗尽 或 出现了时钟回拨问题
     * block current thread if the QPS of acquiring UUID is too high
     * that current sequence space is exhausted
     */
    private void waitIfNecessary() {
        // 获取当前时间 以及相应的sequence序列号
        long currentWithSequence = timestampAndSequence.get();
        // 通过位运算拿到当前的时间戳
        long current = currentWithSequence >>> sequenceBits;
        // 获取当前真实的时间戳
        long newest = getNewestTimestamp();
        // 如果当前时间戳大于等于真实的时间戳，说明某个毫秒内的sequence序号已经被耗尽了
        if (current >= newest) {
            try {
                // 如果获取UUID的QPS过高，导致时间戳对应的sequence序号被耗尽了
                // 线程休眠5毫秒
                Thread.sleep(5);
            } catch (InterruptedException ignore) {
                // don't care
            }
        }
    }

    /**
     * get newest timestamp relative to twepoch
     */
    private long getNewestTimestamp() {
        return System.currentTimeMillis() - twepoch;
    }

    /**
     * auto generate workerId, try using mac first, if failed, then randomly generate one
     * @return workerId
     */
    private long generateWorkerId() {
        try {
            // 默认基于mac生成
            return generateWorkerIdBaseOnMac();
        } catch (Exception e) {
            return generateRandomWorkerId();
        }
    }

    /**
     * use lowest 10 bit of available MAC as workerId
     * @return workerId
     * @throws Exception when there is no available mac found
     */
    private long generateWorkerIdBaseOnMac() throws Exception {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all.hasMoreElements()) {
            NetworkInterface networkInterface = all.nextElement();
            boolean isLoopback = networkInterface.isLoopback();
            boolean isVirtual = networkInterface.isVirtual();
            if (isLoopback || isVirtual) {
                continue;
            }
            // 正常情况是拿到本地网络的MAC地址，然后基于MAC地址生成workerId
            byte[] mac = networkInterface.getHardwareAddress();
            return ((mac[4] & 0B11) << 8) | (mac[5] & 0xFF);
        }
        throw new RuntimeException("no available mac found");
    }

    /**
     * randomly generate one as workerId
     * @return workerId
     */
    private long generateRandomWorkerId() {
        return new Random().nextInt(maxWorkerId + 1);
    }
}
