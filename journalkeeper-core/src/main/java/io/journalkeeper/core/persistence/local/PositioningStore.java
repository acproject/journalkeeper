/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.journalkeeper.core.persistence.local;


import io.journalkeeper.core.persistence.IdentifiablePersistence;
import io.journalkeeper.core.persistence.PersistenceID;
import io.journalkeeper.core.persistence.StoreFile;
import io.journalkeeper.core.persistence.StringPersistenceID;
import io.journalkeeper.core.persistence.cache.MemoryCacheManager;
import io.journalkeeper.core.persistence.JournalPersistence;
import io.journalkeeper.core.persistence.MonitoredPersistence;
import io.journalkeeper.core.persistence.TooManyBytesException;
import io.journalkeeper.core.persistence.journal.CorruptedStoreException;
import io.journalkeeper.core.persistence.journal.DiskFullException;
import io.journalkeeper.core.persistence.journal.PositionOverflowException;
import io.journalkeeper.core.persistence.journal.PositionUnderflowException;
import io.journalkeeper.utils.ThreadSafeFormat;
import io.journalkeeper.utils.spi.ServiceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带缓存的、无锁、高性能、多文件、基于位置的、Append Only的日志存储存储。
 * @author LiYue
 * Date: 2018/8/14
 */
public class PositioningStore implements JournalPersistence, MonitoredPersistence, IdentifiablePersistence, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(PositioningStore.class);
    private static final String LOCAL_PERSISTENCE_ID = "local";
    private final MemoryCacheManager bufferPool;
    private final NavigableMap<Long, StoreFile> storeFileMap = new ConcurrentSkipListMap<>();
    // 删除和回滚不能同时操作fileMap，需要做一下互斥。
    private final Object fileMapMutex = new Object();    // 正在写入的
    private File base;
    private final AtomicLong flushPosition = new AtomicLong(0L);
    private final AtomicLong writePosition = new AtomicLong(0L);
    private final AtomicLong leftPosition = new AtomicLong(0L);
    private StoreFile writeStoreFile = null;
    private Config config = null;

    public PositioningStore() {
        this.bufferPool = ServiceSupport.load(MemoryCacheManager.class);
    }


    /**
     * 将位置回滚到position
     * 与如下操作不能并发：
     * flush()
     * append()
     */
    public void truncate(long givenMax) throws IOException {
        synchronized (fileMapMutex) {
            if (givenMax == max()) return;
            logger.info("Truncate to position: {}, min: {}, max: {}, flushed: {}, path: {}...",
                    ThreadSafeFormat.formatWithComma(givenMax),
                    ThreadSafeFormat.formatWithComma(min()),
                    ThreadSafeFormat.formatWithComma(max()),
                    ThreadSafeFormat.formatWithComma(flushPosition.get()),
                    base.getAbsolutePath());

            if (givenMax < min() || givenMax > max()) {
                throw new IllegalArgumentException(
                        String.format("GivenMax %s should between [%s, %s]!",
                                ThreadSafeFormat.formatWithComma(givenMax),
                                ThreadSafeFormat.formatWithComma(min()),
                                ThreadSafeFormat.formatWithComma(max())
                        )
                );
            } else if (givenMax < max()) {
                rollbackFiles(givenMax);
                this.writePosition.set(givenMax);
                if (this.flushPosition.get() > givenMax) this.flushPosition.set(givenMax);
                resetWriteStoreFile();
            }
        }
    }

    private void clearData() throws IOException {
        for (StoreFile storeFile : this.storeFileMap.values()) {
            if (storeFile.hasPage()) storeFile.unload();
            if(Files.exists(storeFile.path())) {
                Files.delete(storeFile.path());
                logger.debug("File {} deleted.", storeFile.path());
            }
        }
        this.storeFileMap.clear();
        this.writeStoreFile = null;
    }

    public void delete() throws IOException {
        clearData();
        if (base.exists() && !base.delete()) {
            throw new IOException(String.format("Can not delete Directory: %s.", base.getAbsolutePath()));
        }
    }

    private void rollbackFiles(long position) throws IOException {

        if (!storeFileMap.isEmpty()) {
            // position 所在的Page需要截断至position
            Map.Entry<Long, StoreFile> entry = storeFileMap.floorEntry(position);
            StoreFile storeFile = entry.getValue();
            if (position > storeFile.position()) {
                int relPos = (int) (position - storeFile.position());
                logger.info("Truncate store file {} to relative position {}.", storeFile.path(), relPos);
                storeFile.rollback(relPos);
            }

            SortedMap<Long, StoreFile> toBeRemoved = storeFileMap.tailMap(position);

            for (StoreFile sf : toBeRemoved.values()) {
                logger.info("Delete store file {}.", sf.path());
                forceDeleteStoreFile(sf);
                if (writeStoreFile == sf) {
                    writeStoreFile = null;
                }
            }
            toBeRemoved.clear();
        }


    }


    private void resetWriteStoreFile() {
        if (!storeFileMap.isEmpty()) {
            StoreFile storeFile = storeFileMap.lastEntry().getValue();
            if (storeFile.position() + storeFile.size() > writePosition.get()) {
                writeStoreFile = storeFile;
            }
        }
    }

    public void recover(Path path, long min, Properties properties) throws IOException {
        Files.createDirectories(path);
        this.base = path.toFile();
        this.config = toConfig(properties);

        bufferPool.addPreLoad(config.getFileDataSize(), config.getCachedFileCoreCount(), config.getCachedFileMaxCount());

        recoverFileMap(min);

        long recoverPosition = this.storeFileMap.isEmpty() ? min : this.storeFileMap.lastKey() + this.storeFileMap.lastEntry().getValue().fileDataSize();
        flushPosition.set(recoverPosition);
        writePosition.set(recoverPosition);

        leftPosition.set(this.storeFileMap.isEmpty() ? min : this.storeFileMap.firstKey());

        resetWriteStoreFile();
        if (logger.isDebugEnabled()) {
            logger.debug("Store loaded, left: {}, right: {},  base: {}.",
                    ThreadSafeFormat.formatWithComma(min()),
                    ThreadSafeFormat.formatWithComma(max()),
                    base.getAbsolutePath());
        }
    }

    private Config toConfig(Properties properties) {
        Config config = new Config();

        config.setFileDataSize(Integer.parseInt(
                properties.getProperty(
                        Config.FILE_DATA_SIZE_KEY,
                        String.valueOf(Config.DEFAULT_FILE_DATA_SIZE))));
        config.setFileHeaderSize(Integer.parseInt(
                properties.getProperty(
                        Config.FILE_HEADER_SIZE_KEY,
                        String.valueOf(Config.DEFAULT_FILE_HEADER_SIZE))));

        config.setCachedFileCoreCount(Integer.parseInt(
                properties.getProperty(
                        Config.CACHED_FILE_CORE_COUNT_KEY,
                        String.valueOf(Config.DEFAULT_CACHED_FILE_CORE_COUNT))));

        config.setCachedFileMaxCount(Integer.parseInt(
                properties.getProperty(
                        Config.CACHED_FILE_MAX_COUNT_KEY,
                        String.valueOf(Config.DEFAULT_CACHED_FILE_MAX_COUNT))));

        config.setMaxDirtySize(Long.parseLong(
                properties.getProperty(
                        Config.MAX_DIRTY_SIZE_KEY,
                        String.valueOf(Config.DEFAULT_MAX_DIRTY_SIZE))));

        return config;
    }

    private void recoverFileMap(long min) throws IOException {
        File[] files = base.listFiles(file -> file.isFile() && file.getName().matches("\\d+"));
        long filePosition;
        if (null != files) {
            for (File file : files) {
                filePosition = Long.parseLong(file.getName());
                if (filePosition >= min || filePosition + file.length() - config.getFileHeaderSize() > min) {
                    storeFileMap.put(filePosition, new LocalStoreFile(filePosition, base.toPath().resolve(String.valueOf(filePosition)), config.getFileHeaderSize(), bufferPool, config.getFileDataSize()));
                } else {
                    logger.info("Ignore file {}, cause file position is smaller than given min position {}.", file.getAbsolutePath(), min);
                }
            }
        }

        // 检查文件是否连续完整
        if (!storeFileMap.isEmpty()) {
            long position = storeFileMap.firstKey();
            for (Map.Entry<Long, StoreFile> fileEntry : storeFileMap.entrySet()) {
                if (position != fileEntry.getKey()) {
                    throw new CorruptedStoreException(String.format("Files are not continuous! expect: %d, actual file name: %d, store: %s.", position, fileEntry.getKey(), base.getAbsolutePath()));
                }
                position += fileEntry.getValue().fileDataSize();
            }
        }
    }


    @Override
    public long append(byte[] bytes) throws IOException {

        if (bytes.length > config.fileDataSize) {
            throw new TooManyBytesException(bytes.length, config.fileDataSize, base.toPath());
        }

        // Wait for flush
        maybeWaitForFlush();

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (null == writeStoreFile) writeStoreFile = createStoreFile(writePosition.get());
        if (writeStoreFile.size() - writeStoreFile.writePosition() < buffer.remaining()) {
            writeStoreFile.closeWrite();
            writeStoreFile = createStoreFile(writePosition.get());
        }

        return writePosition.addAndGet(writeStoreFile.append(buffer));
    }


    @Override
    public long append(List<byte[]> bytesList ) throws IOException {

        int totalLength = 0;

        List<ByteBuffer> byteBufferList = new ArrayList<>(bytesList.size());
        for (byte[] bytes : bytesList) {
            totalLength += bytes.length;
            byteBufferList.add(ByteBuffer.wrap(bytes));
        }

        if (totalLength > config.fileDataSize) {
            throw new TooManyBytesException(totalLength, config.fileDataSize, base.toPath());
        }

        // Wait for flush
        maybeWaitForFlush();


        if (null == writeStoreFile) writeStoreFile = createStoreFile(writePosition.get());
        if (writeStoreFile.size() - writeStoreFile.writePosition() < totalLength) {
            writeStoreFile.closeWrite();
            writeStoreFile = createStoreFile(writePosition.get());
        }

        return writePosition.addAndGet(writeStoreFile.append(byteBufferList));
    }


    private void maybeWaitForFlush() {
        while (config.getMaxDirtySize() > 0 && max() - flushed() > config.getMaxDirtySize()) {
            Thread.yield();
        }
    }


    @Override
    public long min() {
        return leftPosition.get();
    }

    @Override
    public long physicalMin() {
        return storeFileMap.isEmpty() ? min() : storeFileMap.firstKey();
    }

    @Override
    public long max() {
        return writePosition.get();
    }

    @Override
    public long flushed() {
        return flushPosition.get();
    }

    @Override
    public void flush() throws IOException {
        if (flushPosition.get() < writePosition.get()) {
            Map.Entry<Long, StoreFile> entry = storeFileMap.floorEntry(flushPosition.get());
            if (null == entry) return;
            StoreFile storeFile = entry.getValue();
            if (!storeFile.isClean()) {
                // 在文件第一次刷盘之前，需要把上一个文件fsync到磁盘上，避免服务器宕机导致文件不连续
                if (storeFile.flushPosition() == 0) {
                    Map.Entry<Long, StoreFile> prevEntry = storeFileMap.floorEntry(entry.getKey() - 1);
                    if(null != prevEntry) {
                        prevEntry.getValue().force();
                    }
                }
                storeFile.flush();
            }
            if (flushPosition.get() < storeFile.position() + storeFile.flushPosition()) {
                flushPosition.set(storeFile.position() + storeFile.flushPosition());
            }
        }
    }

    private StoreFile createStoreFile(long position) throws IOException {
        StoreFile storeFile = new LocalStoreFile(
                position, base.toPath().resolve(String.valueOf(position)),
                config.getFileHeaderSize(), bufferPool, config.getFileDataSize());
        StoreFile present;
        if ((present = storeFileMap.putIfAbsent(position, storeFile)) != null) {
            storeFile = present;
        } else {
            checkDiskFreeSpace(base, config.getFileDataSize() + config.getFileHeaderSize());
        }
        return storeFile;
    }

    private void checkDiskFreeSpace(File file, long fileSize) {
        if (file.getFreeSpace() < fileSize) {
            throw new DiskFullException(file);
        }
    }

    public byte[] read(long position, int length) throws IOException {
        if (length == 0) return new byte[0];
        checkReadPosition(position);
        StoreFile storeFile = getStoreFile(position);
        if(null == storeFile) {
            return null;
        }
        int relPosition = (int) (position - storeFile.position());
        return storeFile.read(relPosition, length).array();
    }

    public Long readLong(long position) throws IOException {
        checkReadPosition(position);
        StoreFile storeFile = getStoreFile(position);
        if(null == storeFile) {
            return null;
        }
        int relPosition = (int) (position - storeFile.position());
        return storeFile.readLong(relPosition);
    }

    private StoreFile getStoreFile(long position) {
        Map.Entry<Long, StoreFile> storeFileEntry = storeFileMap.floorEntry(position);
        if (storeFileEntry == null) {
            return null;
        }
        return storeFileEntry.getValue();
    }

    private void checkReadPosition(long position) {
        long p;
        if ((p = min()) > position) {
            throw new PositionUnderflowException(position, p);
        } else if (position >= (p = max())) {
            throw new PositionOverflowException(position, p);
        }

    }


    /**
     * 删除 position之前的文件
     */
    public long compact(long givenMin) throws IOException {
        synchronized (fileMapMutex) {
            if (givenMin <= min()) {
                return 0L;
            }
            if (givenMin > flushPosition.get()) {
                throw new IllegalArgumentException(
                        String.format("GivenMax %s should less than flush position %s!",
                                ThreadSafeFormat.formatWithComma(givenMin),
                                ThreadSafeFormat.formatWithComma(flushPosition.get())
                        )
                );
            }

            leftPosition.set(givenMin);
            Iterator<Map.Entry<Long, StoreFile>> iterator =
                    storeFileMap.entrySet().iterator();
            long deleteSize = 0L;

            while (iterator.hasNext()) {
                Map.Entry<Long, StoreFile> entry = iterator.next();
                StoreFile storeFile = entry.getValue();
                long start = entry.getKey();
                long fileDataSize = storeFile.hasPage() ? storeFile.writePosition() : storeFile.fileDataSize();

                if (start + fileDataSize > givenMin) break;
                iterator.remove();
                forceDeleteStoreFile(storeFile);
                deleteSize += fileDataSize;
            }

            return deleteSize;
        }
    }

    @Override
    public void appendFile(Path srcPath) {
        throw new UnsupportedOperationException();
    }


    /**
     * 删除文件，丢弃未刷盘的数据，用于rollback
     */
    private void forceDeleteStoreFile(StoreFile storeFile) throws IOException {
        storeFile.forceUnload();
        if(Files.exists(storeFile.path())) {
            Files.delete(storeFile.path());
            logger.debug("File {} deleted.", storeFile.path());
        }
    }

    @Override
    public Path getBasePath() {
        return base.toPath();
    }

    @Override
    public List<StoreFile> getStoreFiles() {
        return Collections.unmodifiableList(
                new ArrayList<>(storeFileMap.values())
        );
    }

    @Override
    public void close() throws IOException {
        for (StoreFile storeFile : storeFileMap.values()) {
            storeFile.flush();
            storeFile.forceUnload();
        }
        bufferPool.removePreLoad(config.fileDataSize);
    }

    @Override
    public Path getPath() {
        return getBasePath();
    }

    @Override
    public long getFreeSpace() {
        return base.getFreeSpace();
    }

    @Override
    public long getTotalSpace() {
        return base.getTotalSpace();
    }

    @Override
    public String toString() {
        return "PositioningStore{" +
                "flushPosition=" + flushPosition +
                ", writePosition(max)=" + writePosition +
                ", leftPosition(min)=" + leftPosition +
                '}';
    }

    @Override
    public PersistenceID getID() {
        return new StringPersistenceID(LOCAL_PERSISTENCE_ID);
    }

    public static class Config {
        final static int DEFAULT_FILE_HEADER_SIZE = 128;
        final static int DEFAULT_FILE_DATA_SIZE = 128 * 1024 * 1024;
        final static int DEFAULT_CACHED_FILE_CORE_COUNT = 0;
        final static int DEFAULT_CACHED_FILE_MAX_COUNT = 2;
        final static long DEFAULT_MAX_DIRTY_SIZE = 0L;
        final static String FILE_HEADER_SIZE_KEY = "file_header_size";
        final static String FILE_DATA_SIZE_KEY = "file_data_size";
        final static String CACHED_FILE_CORE_COUNT_KEY = "cached_file_core_count";
        final static String CACHED_FILE_MAX_COUNT_KEY = "cached_file_max_count";
        final static String MAX_DIRTY_SIZE_KEY = "max_dirty_size";
        /**
         * 文件头长度
         */
        private int fileHeaderSize;
        /**
         * 文件内数据最大长度
         */
        private int fileDataSize;

        /**
         * 缓存文件的核心数量。
         */
        private int cachedFileCoreCount;
        /**
         * 缓存文件的最大数量。
         */
        private int cachedFileMaxCount;

        /**
         * 脏数据最大长度，超过这个长度append将阻塞
         */
        private long maxDirtySize;

        int getFileHeaderSize() {
            return fileHeaderSize;
        }

        void setFileHeaderSize(int fileHeaderSize) {
            this.fileHeaderSize = fileHeaderSize;
        }

        int getFileDataSize() {
            return fileDataSize;
        }

        void setFileDataSize(int fileDataSize) {
            this.fileDataSize = fileDataSize;
        }

        int getCachedFileCoreCount() {
            return cachedFileCoreCount;
        }

        void setCachedFileCoreCount(int cachedFileCoreCount) {
            this.cachedFileCoreCount = cachedFileCoreCount;
        }

        int getCachedFileMaxCount() {
            return cachedFileMaxCount;
        }

        void setCachedFileMaxCount(int cachedFileMaxCount) {
            this.cachedFileMaxCount = cachedFileMaxCount;
        }

        public long getMaxDirtySize() {
            return maxDirtySize;
        }

        public void setMaxDirtySize(long maxDirtySize) {
            this.maxDirtySize = maxDirtySize;
        }
    }
}
