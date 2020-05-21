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
package io.journalkeeper.core.persistence.journal;


import io.journalkeeper.core.persistence.JournalPersistence;
import io.journalkeeper.core.persistence.MonitoredPersistence;
import io.journalkeeper.core.persistence.StoreFile;
import io.journalkeeper.core.persistence.remote.DistributedImmutableStoreFile;
import io.journalkeeper.utils.ThreadSafeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 只读的分布式Journal存储
 * @author LiYue
 * Date: 2018/8/14
 */
public class DistributedJournalPersistence implements JournalPersistence, MonitoredPersistence {
    private static final Logger logger = LoggerFactory.getLogger(DistributedJournalPersistence.class);
    private final NavigableMap<Long, StoreFile> storeFileMap = new ConcurrentSkipListMap<>();
    private Path base;
    private Config config = null;
    private final AtomicLong min = new AtomicLong(0L);

    public void truncate(long givenMax) {
        throw new UnsupportedOperationException();
    }

    private void clearData() throws IOException {
        for (StoreFile storeFile : this.storeFileMap.values()) {
            if (storeFile.hasPage()) storeFile.unload();
            File file = storeFile.file();
            if (file.exists() && !file.delete())
                throw new IOException(String.format("Can not delete file: %s.", file.getAbsolutePath()));
        }
        this.storeFileMap.clear();
    }

    public void delete() throws IOException {
        clearData();
        Files.delete(base);
    }

    public void recover(Path path, long min, Properties properties) throws IOException {
        Files.createDirectories(path);
        this.base = path;
        this.config = toConfig(properties);
        this.min.set(min);
        recoverFileMap(min);

        if (logger.isDebugEnabled()) {
            logger.debug("Store loaded, left: {}, right: {},  base: {}.",
                    ThreadSafeFormat.formatWithComma(min()),
                    ThreadSafeFormat.formatWithComma(max()),
                    base);
        }
    }

    private Config toConfig(Properties properties) {
        Config config = new Config();

        config.setFileHeaderSize(Integer.parseInt(
                properties.getProperty(
                        Config.FILE_HEADER_SIZE_KEY,
                        String.valueOf(Config.DEFAULT_FILE_HEADER_SIZE))));

        return config;
    }

    private void recoverFileMap(long min) throws IOException {

        List<Path> paths = Files.list(base)
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("\\d+"))
                .collect(Collectors.toList());

        long filePosition;
        for (Path path : paths) {
            filePosition = Long.parseLong(path.getFileName().toString());
            if (filePosition >= min || filePosition + Files.size(path) - config.getFileHeaderSize() > min) {
                storeFileMap.put(filePosition, new DistributedImmutableStoreFile(filePosition, path, config.getFileHeaderSize()));
            } else {
                logger.info("Ignore file {}, cause file position is smaller than given min position {}.", path, min);
            }
        }

        // 检查文件是否连续完整
        if (!storeFileMap.isEmpty()) {
            long position = storeFileMap.firstKey();
            for (Map.Entry<Long, StoreFile> fileEntry : storeFileMap.entrySet()) {
                if (position != fileEntry.getKey()) {
                    throw new CorruptedStoreException(String.format("Files are not continuous! expect: %d, actual file name: %d, store: %s.", position, fileEntry.getKey(), base));
                }
                position += fileEntry.getValue().file().length() - config.getFileHeaderSize();
            }
        }
    }

    public void appendFile(Path path) throws IOException {
        if (String.valueOf(max()).equals(path.getFileName().toString()) || max() == 0L) {
            long filePosition = Long.parseLong(path.getFileName().toString());
            storeFileMap.put(filePosition, new DistributedImmutableStoreFile(filePosition, path, config.getFileHeaderSize()));
        } else {
            throw new IllegalArgumentException(
                    String.format("Append file failed, cause: invalid file name: %s, expected file name: %s!",
                            path.getFileName().toString(),
                            max()
                    )
            );
        }
    }

    @Override
    public long append(byte[] bytes) {
        throw new UnsupportedOperationException();
    }


    @Override
    public long append(List<byte[]> bytesList ) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long min() {
        return min.get();
    }

    @Override
    public long physicalMin() {
        return storeFileMap.isEmpty() ? min.get() : storeFileMap.firstKey();
    }

    @Override
    public long max() {
        return storeFileMap.isEmpty() ? min.get() : storeFileMap.lastKey() + storeFileMap.lastEntry().getValue().fileDataSize();
    }

    @Override
    public long flushed() {
        return max();
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException();    }

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
            if (givenMin <= min()) {
                return 0L;
            }
            if (givenMin > max()) {
                throw new IllegalArgumentException(
                        String.format("GivenMax %s should less than max position %s!",
                                ThreadSafeFormat.formatWithComma(givenMin),
                                ThreadSafeFormat.formatWithComma(max())
                        )
                );
            }

            min.set(givenMin);
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


    /**
     * 删除文件，丢弃未刷盘的数据，用于rollback
     */
    private void forceDeleteStoreFile(StoreFile storeFile) throws IOException {
        storeFile.forceUnload();
        File file = storeFile.file();
        if (file.exists()) {
            if (file.delete()) {
                logger.debug("File {} deleted.", file.getAbsolutePath());
            } else {
                throw new IOException(String.format("Delete file %s failed!", file.getAbsolutePath()));
            }
        }
    }

    @Override
    public Path getBasePath() {
        return base;
    }

    @Override
    public List<File> getFileList() {
        return Collections.unmodifiableList(
                storeFileMap.values().stream()
                        .map(StoreFile::file)
                        .collect(Collectors.toList())
        );
    }

    @Override
    public void close() {
    }

    @Override
    public Path getPath() {
        return getBasePath();
    }

    @Override
    public long getFreeSpace() throws IOException {
        return Files.getFileStore(base).getUsableSpace();
    }

    @Override
    public long getTotalSpace() throws IOException {
        return Files.getFileStore(base).getTotalSpace();
    }


    public static class Config {
        final static int DEFAULT_FILE_HEADER_SIZE = 128;
        final static String FILE_HEADER_SIZE_KEY = "file_header_size";
        /**
         * 文件头长度
         */
        private int fileHeaderSize;

        int getFileHeaderSize() {
            return fileHeaderSize;
        }

        void setFileHeaderSize(int fileHeaderSize) {
            this.fileHeaderSize = fileHeaderSize;
        }

    }
}
