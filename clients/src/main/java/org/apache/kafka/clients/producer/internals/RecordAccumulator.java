/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import java.util.Iterator;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.utils.CopyOnWriteMap;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class acts as a queue that accumulates records into {@link org.apache.kafka.common.record.MemoryRecords}
 * instances to be sent to the server.
 * <p>
 * The accumulator uses a bounded amount of memory and append calls will block when that memory is exhausted, unless
 * this behavior is explicitly disabled.
 */
public final class RecordAccumulator {

    private static final Logger log = LoggerFactory.getLogger(RecordAccumulator.class);

    private volatile boolean closed;
    private final AtomicInteger flushesInProgress;
    // 记录正在向RecordAccumulator追加消息的线程数
    private final AtomicInteger appendsInProgress;
    // 指定每个RecordBatch底层ByteBuffer的大小
    private final int batchSize;
    // 压缩类型
    private final CompressionType compression;
    private final long lingerMs;
    private final long retryBackoffMs;
    // 使用BufferPool缓冲池
    private final BufferPool free;
    private final Time time;
    // 字典的值缓存的是发往对应的TopicPartition中的消息
    private final ConcurrentMap<TopicPartition, Deque<RecordBatch>> batches;
    // 未发送完成的RecordBatch集合，底层是一个Set集合
    private final IncompleteRecordBatches incomplete;
    // The following variables are only accessed by the sender thread, so we don't need to protect them.
    private final Set<TopicPartition> muted;
    // 使用drain方法批量导出RecordBatch时，为防止饥饿，使用该字段记录上次发送停止时的位置，下次继续从此位置开始发送
    private int drainIndex;

    /**
     * Create a new record accumulator
     * 
     * @param batchSize The size to use when allocating {@link org.apache.kafka.common.record.MemoryRecords} instances
     * @param totalSize The maximum memory the record accumulator can use.
     * @param compression The compression codec for the records
     * @param lingerMs An artificial delay time to add before declaring a records instance that isn't full ready for
     *        sending. This allows time for more records to arrive. Setting a non-zero lingerMs will trade off some
     *        latency for potentially better throughput due to more batching (and hence fewer, larger requests).
     * @param retryBackoffMs An artificial delay time to retry the produce request upon receiving an error. This avoids
     *        exhausting all retries in a short period of time.
     * @param metrics The metrics
     * @param time The time instance to use
     */
    public RecordAccumulator(int batchSize,
                             long totalSize,
                             CompressionType compression,
                             long lingerMs,
                             long retryBackoffMs,
                             Metrics metrics,
                             Time time) {
        this.drainIndex = 0;
        this.closed = false;
        this.flushesInProgress = new AtomicInteger(0);
        this.appendsInProgress = new AtomicInteger(0);
        this.batchSize = batchSize;
        this.compression = compression;
        this.lingerMs = lingerMs;
        this.retryBackoffMs = retryBackoffMs;
        this.batches = new CopyOnWriteMap<>();
        String metricGrpName = "producer-metrics";
        this.free = new BufferPool(totalSize, batchSize, metrics, time, metricGrpName);
        this.incomplete = new IncompleteRecordBatches();
        this.muted = new HashSet<>();
        this.time = time;
        registerMetrics(metrics, metricGrpName);
    }

    private void registerMetrics(Metrics metrics, String metricGrpName) {
        MetricName metricName = metrics.metricName("waiting-threads", metricGrpName, "The number of user threads blocked waiting for buffer memory to enqueue their records");
        Measurable waitingThreads = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.queued();
            }
        };
        metrics.addMetric(metricName, waitingThreads);

        metricName = metrics.metricName("buffer-total-bytes", metricGrpName, "The maximum amount of buffer memory the client can use (whether or not it is currently used).");
        Measurable totalBytes = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.totalMemory();
            }
        };
        metrics.addMetric(metricName, totalBytes);

        metricName = metrics.metricName("buffer-available-bytes", metricGrpName, "The total amount of buffer memory that is not being used (either unallocated or in the free list).");
        Measurable availableBytes = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.availableMemory();
            }
        };
        metrics.addMetric(metricName, availableBytes);

        Sensor bufferExhaustedRecordSensor = metrics.sensor("buffer-exhausted-records");
        metricName = metrics.metricName("buffer-exhausted-rate", metricGrpName, "The average per-second number of record sends that are dropped due to buffer exhaustion");
        bufferExhaustedRecordSensor.add(metricName, new Rate());
    }

    /**
     * Add a record to the accumulator, return the append result
     * <p>
     * The append result will contain the future metadata, and flag for whether the appended batch is full or a new batch is created
     * <p>
     *
     * @param tp The topic/partition to which this record is being sent
     * @param timestamp The timestamp of the record
     * @param key The key for the record
     * @param value The value for the record
     * @param callback The user-supplied callback to execute when the request is complete
     * @param maxTimeToBlock The maximum time in milliseconds to block for buffer memory to be available
     */
    public RecordAppendResult append(TopicPartition tp,
                                     long timestamp,
                                     byte[] key,
                                     byte[] value,
                                     Callback callback,
                                     long maxTimeToBlock) throws InterruptedException {
        // We keep track of the number of appending thread to make sure we do not miss batches in
        // abortIncompleteBatches().
		// 统计正在向RecordAccumulator中追加数据的线程数
        appendsInProgress.incrementAndGet();
        try {
            // check if we have an in-progress batch
			// 步骤1：查找TopicPartition对应的Deque
            Deque<RecordBatch> dq = getOrCreateDeque(tp);
            // 同步操作，以Deque为锁
            synchronized (dq) {
            	// 检查生产者是否已经关闭了
                if (closed)
                    throw new IllegalStateException("Cannot send after the producer is closed.");
                // 使用tryAppend()方法向Deque中最后一个RecordBatch追加Record
                RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
                if (appendResult != null)
                	// 返回值不为空说明添加成功，直接将返回值进行return
                    return appendResult;
            }

            // we don't have an in-progress record batch try to allocate a new batch
			// 走到这里，说明可能没有正在接受Record的RecordBatch，需要分配一个新的RecordBatch
			// 根据消息大小和batchSize，选择一个大的值作为RecordBatch底层ByteBuffer的大小
            int size = Math.max(this.batchSize, Records.LOG_OVERHEAD + Record.recordSize(key, value));
            log.trace("Allocating a new {} byte message buffer for topic {} partition {}", size, tp.topic(), tp.partition());
            // 使用BufferPool缓存池分配ByteBuffer空间
            ByteBuffer buffer = free.allocate(size, maxTimeToBlock);
            // 以dp为锁
            synchronized (dq) {
                // Need to check if producer is closed again after grabbing the dequeue lock.
				// 检查生产者是否已经关闭了
                if (closed)
                    throw new IllegalStateException("Cannot send after the producer is closed.");

                // 再次使用tryAppend()方法向Deque中最后一个RecordBatch追加Record
                RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
                if (appendResult != null) {
                    // Somebody else found us a batch, return the one we waited for! Hopefully this doesn't happen often...
					// 追加成功，释放buffer给BufferPool
                    free.deallocate(buffer);
                    // 返回
                    return appendResult;
                }
                // 走到这里说明追加失败
				// 创建一个MemoryRecords
                MemoryRecords records = MemoryRecords.emptyRecords(buffer, compression, this.batchSize);
                // 使用传入的TopicPartition参数和records新创建一个RecordBatch
                RecordBatch batch = new RecordBatch(tp, records, time.milliseconds());
                // 使用新创建的batch再次尝试
                FutureRecordMetadata future = Utils.notNull(batch.tryAppend(timestamp, key, value, callback, time.milliseconds()));

                // 将新创建的RecordBatch添加到dp及incomplete中
                dq.addLast(batch);
                incomplete.add(batch);
                // 返回
                return new RecordAppendResult(future, dq.size() > 1 || batch.records.isFull(), true);
            }
        } finally {
        	// 将记录正在追加消息的线程数的计数器减1
            appendsInProgress.decrementAndGet();
        }
    }

    /**
     * If `RecordBatch.tryAppend` fails (i.e. the record batch is full), close its memory records to release temporary
     * resources (like compression streams buffers).
     */
    private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, Deque<RecordBatch> deque) {
    	// 获取deque中最后一个RecordBatch
        RecordBatch last = deque.peekLast();
        if (last != null) {
        	// 向last中添加消息，获得返回值future
            FutureRecordMetadata future = last.tryAppend(timestamp, key, value, callback, time.milliseconds());
            if (future == null)
            	// 如果返回的future为空，表示该RecordBatch已经没有足够的空间了，将该RecordBatch的MemoryRecords对象关闭
                last.records.close();
            else
            	// 如果返回有值，说明添加成功，直接返回包装对象RecordAppendResult
                return new RecordAppendResult(future, deque.size() > 1 || last.records.isFull(), false);
        }
        // 如果last为空，说明deque为空，直接返回null
        return null;
    }

    /**
     * Abort the batches that have been sitting in RecordAccumulator for more than the configured requestTimeout
     * due to metadata being unavailable
     */
    public List<RecordBatch> abortExpiredBatches(int requestTimeout, long now) {
        List<RecordBatch> expiredBatches = new ArrayList<>();
        int count = 0;
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            Deque<RecordBatch> dq = entry.getValue();
            TopicPartition tp = entry.getKey();
            // We only check if the batch should be expired if the partition does not have a batch in flight.
            // This is to prevent later batches from being expired while an earlier batch is still in progress.
            // Note that `muted` is only ever populated if `max.in.flight.request.per.connection=1` so this protection
            // is only active in this case. Otherwise the expiration order is not guaranteed.
            if (!muted.contains(tp)) {
                synchronized (dq) {
                    // iterate over the batches and expire them if they have been in the accumulator for more than requestTimeOut
                    RecordBatch lastBatch = dq.peekLast();
                    Iterator<RecordBatch> batchIterator = dq.iterator();
                    while (batchIterator.hasNext()) {
                        RecordBatch batch = batchIterator.next();
                        boolean isFull = batch != lastBatch || batch.records.isFull();
                        // check if the batch is expired
                        if (batch.maybeExpire(requestTimeout, retryBackoffMs, now, this.lingerMs, isFull)) {
                            expiredBatches.add(batch);
                            count++;
                            batchIterator.remove();
                            deallocate(batch);
                        } else {
                            // Stop at the first batch that has not expired.
                            break;
                        }
                    }
                }
            }
        }
        if (!expiredBatches.isEmpty())
            log.trace("Expired {} batches in accumulator", count);

        return expiredBatches;
    }

    /**
     * Re-enqueue the given record batch in the accumulator to retry
     */
    public void reenqueue(RecordBatch batch, long now) {
        batch.attempts++;
        batch.lastAttemptMs = now;
        batch.lastAppendTime = now;
        batch.setRetry();
        Deque<RecordBatch> deque = getOrCreateDeque(batch.topicPartition);
        synchronized (deque) {
            deque.addFirst(batch);
        }
    }

    /**
     * Get a list of nodes whose partitions are ready to be sent, and the earliest time at which any non-sendable
     * partition will be ready; Also return the flag for whether there are any unknown leaders for the accumulated
     * partition batches.
     * <p>
     * A destination node is ready to send data if:
     * <ol>
     * <li>There is at least one partition that is not backing off its send
     * <li><b>and</b> those partitions are not muted (to prevent reordering if
     *   {@value org.apache.kafka.clients.producer.ProducerConfig#MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION}
     *   is set to one)</li>
     * <li><b>and <i>any</i></b> of the following are true</li>
     * <ul>
     *     <li>The record set is full</li>
     *     <li>The record set has sat in the accumulator for at least lingerMs milliseconds</li>
     *     <li>The accumulator is out of memory and threads are blocking waiting for data (in this case all partitions
     *     are immediately considered ready).</li>
     *     <li>The accumulator has been closed</li>
     * </ul>
     * </ol>
	 *
	 * 该方法用于获取当前集群中符合发送消息条件的数据集
     */
    public ReadyCheckResult ready(Cluster cluster, long nowMs) {
    	// 记录可以向哪些Node节点发送数据
        Set<Node> readyNodes = new HashSet<>();
        // 记录下一次需要调用ready()方法的时间间隔
        long nextReadyCheckDelayMs = Long.MAX_VALUE;
        // 根据Metadata元数据中是否有找不到Leader副本的分区
        boolean unknownLeadersExist = false;
        // 是否有线程在阻塞等待BufferPool释放空间
        boolean exhausted = this.free.queued() > 0;
        
        // 遍历batches字典
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
        	// 获取键和值
            TopicPartition part = entry.getKey();
            Deque<RecordBatch> deque = entry.getValue();

            // 查找分区的Leader所在的Node
            Node leader = cluster.leaderFor(part);
            if (leader == null) {
            	// leader为null，将unknownLeadersExist标记为true，之后会触发Metadata的更新
                unknownLeadersExist = true;
            } else if (!readyNodes.contains(leader) && !muted.contains(part)) {
            	// 分区的leader存在，且readyNodes不包含该leader，
            	// 加锁
                synchronized (deque) {
                	// 查看该RecordBatch队列的第一个RecordBatch
                    RecordBatch batch = deque.peekFirst();
                    if (batch != null) {
						/**
						 * 频繁多次请求的间隔是否在退避时间限制之内，是否需要退避
						 * 1. batch.attempts > 0：请求次数大于0时；
						 * 2. batch.lastAttemptMs + retryBackoffMs > nowMs：本次请求与上次请求的间隔小于退避时间retryBackoffMs
						 */
						boolean backingOff = batch.attempts > 0 && batch.lastAttemptMs + retryBackoffMs > nowMs;
						// 本次请求与上次请求的间隔时间
						long waitedTimeMs = nowMs - batch.lastAttemptMs;
						/**
						 * 需要等待的时间，这个值根据是否需要退避，来选择退避时间或者延迟时间
						 * retryBackoffMs：退避时间（用户可配置，retry.backoff.ms）
						 * lingerMs：延迟时间（用户可配置，linger.ms）
						 * backingOff：是否需要退避
						 * 当需要退避时，需要等待的时间为退避时间，否则为延迟时间
						 */
						long timeToWaitMs = backingOff ? retryBackoffMs : lingerMs;
						// 计算剩余时间，需要等待的时间 - 距离上次发送的间隔时间
						long timeLeftMs = Math.max(timeToWaitMs - waitedTimeMs, 0);
	
						// Deque中有多个RecordBatch或是第一个RecordBatch是否满了
						boolean full = deque.size() > 1 || batch.records.isFull();
						// 是否超时，距离上次发送的间隔时间 - 需要等待的时间
						boolean expired = waitedTimeMs >= timeToWaitMs;
                        
                        // 得到条件
                        boolean sendable = full || expired || exhausted
								|| closed // Sender线程是否准备关闭
								|| flushInProgress(); // 是否有线程正在等待flush操作完成
                        if (sendable && !backingOff) {
                            readyNodes.add(leader);
                        } else {
                            // Note that this results in a conservative estimate since an un-sendable partition may have
                            // a leader that will later be found to have sendable data. However, this is good enough
                            // since we'll just wake up and then sleep again for the remaining time.
							// 记录下次需要调用ready()方法检查的时间间隔
							nextReadyCheckDelayMs = Math.min(timeLeftMs, nextReadyCheckDelayMs);
                        }
                    }
                }
            }
        }

        return new ReadyCheckResult(readyNodes, nextReadyCheckDelayMs, unknownLeadersExist);
    }

    /**
     * @return Whether there is any unsent record in the accumulator.
     */
    public boolean hasUnsent() {
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            Deque<RecordBatch> deque = entry.getValue();
            synchronized (deque) {
                if (!deque.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Drain all the data for the given nodes and collate them into a list of batches that will fit within the specified
     * size on a per-node basis. This method attempts to avoid choosing the same topic-node over and over.
     * 
     * @param cluster The current cluster metadata
     * @param nodes The list of node to drain
     * @param maxSize The maximum number of bytes to drain
     * @param now The current unix time in milliseconds
     * @return A list of {@link RecordBatch} for each node specified with total size less than the requested maxSize.
	 *
	 * 进行映射转换，将TopicPartition -> Deque<RecordBatch> 转换为 NodeId -> List<RecordBatch>
     */
    public Map<Integer, List<RecordBatch>> drain(Cluster cluster,
                                                 Set<Node> nodes,
                                                 int maxSize,
                                                 long now) {
        if (nodes.isEmpty())
            return Collections.emptyMap();
		// 转换后的结果
        Map<Integer, List<RecordBatch>> batches = new HashMap<>();
        for (Node node : nodes) { // 遍历Node集合
            int size = 0;
            // 获取Node上的分区集合
            List<PartitionInfo> parts = cluster.partitionsForNode(node.id());
            // 记录要发送的RecordBatch的集合
            List<RecordBatch> ready = new ArrayList<>();
            /* to make starvation less likely this loop doesn't start at 0 */
            int start = drainIndex = drainIndex % parts.size();
            do {
            	// 获取分区详细信息
                PartitionInfo part = parts.get(drainIndex);
                // 创建TopicPartition
                TopicPartition tp = new TopicPartition(part.topic(), part.partition());
                // Only proceed if the partition has no in-flight batches.
				// 判断需要发送到的分区是否不可用
                if (!muted.contains(tp)) {
                	// 获取对应的RecordBatch队列
                    Deque<RecordBatch> deque = getDeque(new TopicPartition(part.topic(), part.partition()));
                    if (deque != null) {
                        synchronized (deque) { // 加锁
                        	// 查看deque队列的队首RecordBatch
                            RecordBatch first = deque.peekFirst();
                            if (first != null) {
                            	// 检查是否需要退避
                                boolean backoff = first.attempts > 0 && first.lastAttemptMs + retryBackoffMs > now;
                                // Only drain the batch if it is not during backoff period.
                                if (!backoff) {
                                    if (size + first.records.sizeInBytes() > maxSize && !ready.isEmpty()) {
                                        // there is a rare case that a single batch size is larger than the request size due
                                        // to compression; in this case we will still eventually send this batch in a single
                                        // request
										// 数据量已满，结束循环
                                        break;
                                    } else {
                                    	// 从deque中获取第一个RecordBatch
                                        RecordBatch batch = deque.pollFirst();
                                        // 关闭Compressor及底层输出流，并将MemoryRecords设置为只读
                                        batch.records.close();
                                        size += batch.records.sizeInBytes();
                                        ready.add(batch);
                                        batch.drainedMs = now;
                                    }
                                }
                            }
                        }
                    }
                }
                // 更新drainIndex
                this.drainIndex = (this.drainIndex + 1) % parts.size();
            } while (start != drainIndex);
            // 记录NodeId与RecordBatch的对应关系
            batches.put(node.id(), ready);
        }
        return batches;
    }

    private Deque<RecordBatch> getDeque(TopicPartition tp) {
        return batches.get(tp);
    }

    /**
     * Get the deque for the given topic-partition, creating it if necessary.
     * 根据TopicPartition获取对应的Deque
     */
    private Deque<RecordBatch> getOrCreateDeque(TopicPartition tp) {
    	// 从batches中尝试获取，batches的结构是ConcurrentMap<TopicPartition, Deque<RecordBatch>>
        Deque<RecordBatch> d = this.batches.get(tp);
        // 如果获取到就返回
        if (d != null)
            return d;
        // 如果没有获取到，就创建一个新的
        d = new ArrayDeque<>();
        // 使用putIfAbsent进行添加，防止并发引起的重复添加
		// putIfAbsent会先判断是否存在，如果存在就返回且不添加新的，否则返回null并添加新的
        Deque<RecordBatch> previous = this.batches.putIfAbsent(tp, d);
        if (previous == null)
            return d;
        else
            return previous;
    }

    /**
     * Deallocate the record batch
     */
    public void deallocate(RecordBatch batch) {
        incomplete.remove(batch);
        free.deallocate(batch.records.buffer(), batch.records.initialCapacity());
    }
    
    /**
     * Are there any threads currently waiting on a flush?
     *
     * package private for test
     */
    boolean flushInProgress() {
        return flushesInProgress.get() > 0;
    }

    /* Visible for testing */
    Map<TopicPartition, Deque<RecordBatch>> batches() {
        return Collections.unmodifiableMap(batches);
    }
    
    /**
     * Initiate the flushing of data from the accumulator...this makes all requests immediately ready
     */
    public void beginFlush() {
        this.flushesInProgress.getAndIncrement();
    }

    /**
     * Are there any threads currently appending messages?
     */
    private boolean appendsInProgress() {
        return appendsInProgress.get() > 0;
    }

    /**
     * Mark all partitions as ready to send and block until the send is complete
     */
    public void awaitFlushCompletion() throws InterruptedException {
        try {
            for (RecordBatch batch : this.incomplete.all())
                batch.produceFuture.await();
        } finally {
            this.flushesInProgress.decrementAndGet();
        }
    }

    /**
     * This function is only called when sender is closed forcefully. It will fail all the
     * incomplete batches and return.
     */
    public void abortIncompleteBatches() {
        // We need to keep aborting the incomplete batch until no thread is trying to append to
        // 1. Avoid losing batches.
        // 2. Free up memory in case appending threads are blocked on buffer full.
        // This is a tight loop but should be able to get through very quickly.
        do {
            abortBatches();
        } while (appendsInProgress());
        // After this point, no thread will append any messages because they will see the close
        // flag set. We need to do the last abort after no thread was appending in case there was a new
        // batch appended by the last appending thread.
        abortBatches();
        this.batches.clear();
    }

    /**
     * Go through incomplete batches and abort them.
     */
    private void abortBatches() {
        for (RecordBatch batch : incomplete.all()) {
            Deque<RecordBatch> dq = getDeque(batch.topicPartition);
            // Close the batch before aborting
            synchronized (dq) {
                batch.records.close();
                dq.remove(batch);
            }
            batch.done(-1L, Record.NO_TIMESTAMP, new IllegalStateException("Producer is closed forcefully."));
            deallocate(batch);
        }
    }

    public void mutePartition(TopicPartition tp) {
        muted.add(tp);
    }

    public void unmutePartition(TopicPartition tp) {
        muted.remove(tp);
    }

    /**
     * Close this accumulator and force all the record buffers to be drained
     */
    public void close() {
        this.closed = true;
    }

    /*
     * Metadata about a record just appended to the record accumulator
     */
    public final static class RecordAppendResult {
        public final FutureRecordMetadata future;
        public final boolean batchIsFull;
        public final boolean newBatchCreated;

        public RecordAppendResult(FutureRecordMetadata future, boolean batchIsFull, boolean newBatchCreated) {
            this.future = future;
            this.batchIsFull = batchIsFull;
            this.newBatchCreated = newBatchCreated;
        }
    }

    /*
     * The set of nodes that have at least one complete record batch in the accumulator
     */
    public final static class ReadyCheckResult {
        public final Set<Node> readyNodes;
        public final long nextReadyCheckDelayMs;
        public final boolean unknownLeadersExist;

        public ReadyCheckResult(Set<Node> readyNodes, long nextReadyCheckDelayMs, boolean unknownLeadersExist) {
            this.readyNodes = readyNodes;
            this.nextReadyCheckDelayMs = nextReadyCheckDelayMs;
            this.unknownLeadersExist = unknownLeadersExist;
        }
    }
    
    /*
     * A threadsafe helper class to hold RecordBatches that haven't been ack'd yet
     */
    private final static class IncompleteRecordBatches {
        private final Set<RecordBatch> incomplete;

        public IncompleteRecordBatches() {
            this.incomplete = new HashSet<RecordBatch>();
        }
        
        public void add(RecordBatch batch) {
            synchronized (incomplete) {
                this.incomplete.add(batch);
            }
        }
        
        public void remove(RecordBatch batch) {
            synchronized (incomplete) {
                boolean removed = this.incomplete.remove(batch);
                if (!removed)
                    throw new IllegalStateException("Remove from the incomplete set failed. This should be impossible.");
            }
        }
        
        public Iterable<RecordBatch> all() {
            synchronized (incomplete) {
                return new ArrayList<>(this.incomplete);
            }
        }
    }

}
