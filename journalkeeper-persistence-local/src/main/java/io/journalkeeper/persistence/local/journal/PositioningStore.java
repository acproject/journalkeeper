/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.journalkeeper.persistence.local.journal;


import io.journalkeeper.persistence.JournalPersistence;
import io.journalkeeper.persistence.MonitoredPersistence;
import io.journalkeeper.persistence.TooManyBytesException;
import io.journalkeeper.utils.ThreadSafeFormat;
import io.journalkeeper.utils.buffer.PreloadBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
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
public class PositioningStore implements JournalPersistence, MonitoredPersistence ,Closeable {
    private final Logger logger = LoggerFactory.getLogger(PositioningStore.class);
    private File base;
    private final PreloadBufferPool bufferPool;
    private final NavigableMap<Long, StoreFile> storeFileMap = new ConcurrentSkipListMap<>();
    // 删除和回滚不能同时操作fileMap，需要做一下互斥。
    private final Object fileMapMutex = new Object();    // 正在写入的
    private Config config = null;
    public PositioningStore() {
        this.bufferPool = PreloadBufferPool.getInstance();
    }
    private AtomicLong leftPosition = new AtomicLong(0L);

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
                    ThreadSafeFormat.formatWithComma(flushed()),
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
            }
        }
    }

    private void clearData() throws IOException {
        for(StoreFile storeFile :this.storeFileMap.values()) {
            forceDeleteStoreFile(storeFile);
        }
        this.storeFileMap.clear();
    }

    public void delete() throws IOException {
        clearData();
        if(base.exists() && !base.delete()){
            throw new IOException(String.format("Can not delete Directory: %s.", base.getAbsolutePath()));
        }
    }

    private void rollbackFiles(long position) throws IOException {

        if(!storeFileMap.isEmpty()) {
            // position 所在的Page需要截断至position
            Map.Entry<Long, StoreFile> entry = storeFileMap.floorEntry(position);
            StoreFile storeFile = entry.getValue();
            if(position > storeFile.position()) {
                int relPos = (int) (position - storeFile.position());
                logger.info("Truncate store file {} to relative position {}.", storeFile.file().getAbsolutePath(), relPos);
                storeFile.rollback(relPos);
            }

            SortedMap<Long, StoreFile> toBeRemoved = storeFileMap.tailMap(position);

            for(StoreFile sf : toBeRemoved.values()) {
                logger.info("Delete store file {}.", sf.file().getAbsolutePath());
                forceDeleteStoreFile(sf);
            }
            toBeRemoved.clear();
        }


    }

    public void recover(Path path, long min, Properties properties) throws IOException {
        Files.createDirectories(path);
        this.base = path.toFile();
        this.config = toConfig(properties);

        bufferPool.addPreLoad(config.getFileDataSize(), config.getCachedFileCoreCount(), config.getCachedFileMaxCount());
        leftPosition.set(min);
        recoverFileMap(min);
        if(logger.isDebugEnabled()) {
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

    private void recoverFileMap(long min) {
        File[] files = base.listFiles(file -> file.isFile() && file.getName().matches("\\d+"));
        long filePosition;
        if(null != files) {
            for (File file : files) {
                filePosition = Long.parseLong(file.getName());
                if(filePosition >= min || filePosition + file.length() - config.getFileHeaderSize() > min) {
                    storeFileMap.put(filePosition, new LocalStoreFile(filePosition, base, config.getFileHeaderSize(), bufferPool, config.getFileDataSize()));
                } else {
                    logger.info("Ignore file {}, cause file position is smaller than given min position {}.", file.getAbsolutePath(), min);
                }
            }
        }

        // 检查文件是否连续完整
        if(!storeFileMap.isEmpty()) {
            long position = storeFileMap.firstKey();
            for (Map.Entry<Long, StoreFile> fileEntry : storeFileMap.entrySet()) {
                if(position != fileEntry.getKey()) {
                    throw new CorruptedStoreException(String.format("Files are not continuous! expect: %d, actual file name: %d, store: %s.", position, fileEntry.getKey(), base.getAbsolutePath()));
                }
                position += fileEntry.getValue().file().length() - config.getFileHeaderSize();
            }
        }
    }


    @Override
    public long append(byte [] bytes) throws IOException{

        if(bytes.length > config.fileDataSize) {
            throw new TooManyBytesException(bytes.length, config.fileDataSize, base.toPath());
        }
        // Wait for flush
        maybeWaitForFlush();

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        StoreFile writeStoreFile = currentWriteFile();
        if (config.getFileDataSize() - writeStoreFile.writePosition() < buffer.remaining()) {
            writeStoreFile.closeWrite();
            writeStoreFile = createStoreFile();
        }
        writeStoreFile.append(buffer);
        return writeStoreFile.position() + writeStoreFile.writePosition();
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
        Map.Entry<Long, StoreFile> lastEntry = storeFileMap.lastEntry();
        return lastEntry == null ? min() : lastEntry.getValue().position() + lastEntry.getValue().writePosition();
    }

    @Override
    public long flushed() {
        StoreFile flushFile = nextFlushFile();
        return flushFile == null ? max() : flushFile.position() + flushFile.flushPosition();
    }

    private StoreFile nextFlushFile() {
        StoreFile last = null;
        for (Map.Entry<Long, StoreFile> entry : storeFileMap.descendingMap().entrySet()) {
            if (entry.getValue().isClean()) {
                break;
            }
            last = entry.getValue();
        }
        return last;
    }

    @Override
    public void flush() throws IOException {
        StoreFile flushFile = nextFlushFile();
        while (flushFile != null) {
            final boolean writeClosed = flushFile.writeClosed();
            flushFile.flush();
            if (writeClosed) {
                flushFile = storeFileMap.get(flushFile.position() + flushFile.writePosition());
            } else {
                break;
            }
        }
    }

    private StoreFile currentWriteFile() {
        Map.Entry<Long, StoreFile> last = storeFileMap.lastEntry();
        StoreFile storeFile;
        if (last != null && !(storeFile = last.getValue()).writeClosed()) {
           return storeFile;
        } else {
            return createStoreFile();
        }
    }

    private StoreFile createStoreFile() {
        long position = max();
        StoreFile storeFile = new LocalStoreFile(position, base, config.getFileHeaderSize(), bufferPool, config.getFileDataSize());
        StoreFile present;
        if((present = storeFileMap.putIfAbsent(position, storeFile)) != null){
            storeFile = present;
        }
        return storeFile;
    }

    public byte [] read(long position, int length) throws IOException{
        if(length == 0) return new byte [0];
        checkReadPosition(position);
        Map.Entry<Long, StoreFile> storeFileEntry = storeFileMap.floorEntry(position);
        if (storeFileEntry == null) {
            return null;
        }

        StoreFile storeFile = storeFileEntry.getValue();
        int relPosition = (int )(position - storeFile.position());
        return storeFile.read(relPosition, length).array();
    }



    private void checkReadPosition(long position){
        long p;
        if((p = min()) > position) {
            throw new PositionUnderflowException(position, p);
        } else if(position >= (p = max())) {
            throw new PositionOverflowException(position, p);
        }

    }


    /**
     * 删除 position之前的文件
     */
    public long compact(long givenMin) throws IOException {
        synchronized (fileMapMutex) {
            if( givenMin <= min()) {
                return 0L;
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


    /**
     * 删除文件，丢弃未刷盘的数据，用于rollback
     */
    private void forceDeleteStoreFile(StoreFile storeFile) throws IOException {
        storeFile.forceUnload();
        File file = storeFile.file();
        if(file.exists()) {
            if (file.delete()) {
                logger.debug("File {} deleted.", file.getAbsolutePath());
            } else {
                throw new IOException(String.format("Delete file %s failed!", file.getAbsolutePath()));
            }
        }
    }

    @Override
    public Path getBasePath() {
        return base.toPath();
    }

    @Override
    public void close() throws IOException {
        for(StoreFile storeFile : storeFileMap.values()) {
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

    @Override
    public String toString() {
        return "PositioningStore{" +
                "flushPosition=" + flushed() +
                ", writePosition(max)=" + max() +
                ", leftPosition(min)=" + min() +
                '}';
    }
}
