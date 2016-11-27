package ru.mipt.java2016.homework.g597.grishutin.task3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import ru.mipt.java2016.homework.base.task2.KeyValueStorage;
import ru.mipt.java2016.homework.g597.grishutin.task2.SerializationStrategy;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class LargeKeyValueStorage<K, V> implements KeyValueStorage<K, V>, Closeable {

    private final SerializationStrategy<K> keySerializer;
    private final SerializationStrategy<V> valueSerializer;

    private final RandomAccessFile offsetsFile;
    private final RandomAccessFile valuesFile;

    private final Map<K, Long> valueOffsets = new HashMap<>();

    private ReadWriteLock lock;
    private boolean isOpen = false;

    private static final String FILENAME_PREFIX = "AzazaDB";
    private static final String VALUES_FILENAME = FILENAME_PREFIX + ".values";
    private static final String OFFSETS_FILENAME = FILENAME_PREFIX + ".valueOffsets";

    private static final String VALUES_SWAP_FILENAME = VALUES_FILENAME + ".tmp";
    private static final String OFFSETS_SWAP_FILENAME = OFFSETS_FILENAME + ".tmp";

    private LoadingCache<K, V> cached;


    LargeKeyValueStorage(String path,
                         SerializationStrategy<K> keySerializerInit,
                         SerializationStrategy<V> valueSerializerInit) throws IOException {

        keySerializer = keySerializerInit;
        valueSerializer = valueSerializerInit;

        Path valuesPath = Paths.get(path, VALUES_FILENAME);
        Path offsetsPath = Paths.get(path, OFFSETS_FILENAME);

        Files.createDirectories(valuesPath.getParent());

        if (!(Files.exists(valuesPath))) {
            Files.createFile(valuesPath);
        }
        if (!(Files.exists(offsetsPath))) {
            Files.createFile(offsetsPath);
        }

        offsetsFile = new RandomAccessFile(offsetsPath.toFile(), "rw");
        valuesFile = new RandomAccessFile(valuesPath.toFile(), "rw");

        offsetsFile.getChannel().lock();

        lock = new ReentrantReadWriteLock();

        cached = CacheBuilder.newBuilder().maximumSize(100).build(new CacheLoader<K, V>() {
            @Override
            public V load(K key)  {
                Long offset = valueOffsets.get(key);
                if (offset == null) {
                    return null;
                }

                try {
                    valuesFile.seek(offset);
                    return valueSerializer.deserialize(valuesFile);
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }

            }
        });

        isOpen = true;
        readEntriesFromDisk();
    }

    private void checkOpened() {
        if (!isOpen) {
            throw new RuntimeException("Storage is closed");
        }
    }

    private void readEntriesFromDisk() throws IOException {
        valueOffsets.clear();
        offsetsFile.seek(0);
        cached.cleanUp();

        while (offsetsFile.getFilePointer() < offsetsFile.length()) {
            K key = keySerializer.deserialize(offsetsFile);
            Long offset = offsetsFile.readLong();
            valueOffsets.put(key, offset);
        }
    }

    @Override
    public V read(K key) {
        lock.readLock().lock();
        try {
            checkOpened();
            if (!valueOffsets.containsKey(key)) {
                return null;
            }
            return cached.get(key);
        } catch (ExecutionException e) {
            throw new RuntimeException("File operation error");
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void write(K key, V value) {
        lock.writeLock().lock();
        try {
            checkOpened();
            valuesFile.seek(valuesFile.length());
            valueOffsets.put(key, valuesFile.getFilePointer());
            valueSerializer.serialize(value, valuesFile);
        } catch (IOException e) {
            throw new RuntimeException("File operation error");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(K key) {
        lock.writeLock().lock();
        try {
            checkOpened();
            valueOffsets.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean exists(K key) {
        lock.readLock().lock();
        try {
            checkOpened();
            return valueOffsets.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            checkOpened();
            return valueOffsets.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Iterator<K> readKeys() {
        lock.readLock().lock();
        try {
            checkOpened();
            return valueOffsets.keySet().iterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();

        if (!isOpen) {
            return;
        }

        isOpen = false;
        try {
            offsetsFile.setLength(0);
            offsetsFile.seek(0);

            for (Map.Entry<K, Long> entry : valueOffsets.entrySet()) {
                keySerializer.serialize(entry.getKey(), offsetsFile);
                offsetsFile.writeLong(entry.getValue());
            }

            offsetsFile.close();
        } finally {
            lock.writeLock().unlock();
        }
    }
}