package ru.mipt.java2016.homework.g596.gerasimov.task3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import ru.mipt.java2016.homework.base.task2.KeyValueStorage;
import ru.mipt.java2016.homework.g596.gerasimov.task2.Serializer.ISerializer;

/**
 * Created by geras-artem on 16.11.16.
 */
public class SSTableKeyValueStorage<K, V> implements KeyValueStorage<K, V> {
    private final HashMap<K, Long> offsetTable = new HashMap<>();

    private final ISerializer<K> keySerializer;

    private final ISerializer<V> valueSerializer;

    private final IndexFileIO indexFileIO;

    private final StorageFileIO storageFileIO;

    private boolean isClosed = false;

    private boolean offsetTableIsUpdated = false;

    private int deletedCounter = 0;

    private long currentStorageLength;

    private long writtenLength;

    public SSTableKeyValueStorage(String directoryPath, ISerializer<K> keySerializer,
            ISerializer<V> valueSerializer) throws IOException {
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        indexFileIO = new IndexFileIO(directoryPath, "index.db");
        storageFileIO = new StorageFileIO(directoryPath, "storage.db");
        currentStorageLength = storageFileIO.fileLength();
        writtenLength = currentStorageLength - 1;
        readOffsetTable();
    }

    @Override
    public V read(K key) {
        synchronized (offsetTable) {
            checkClosed();
            if (!exists(key)) {
                return null;
            }

            try {
                return readValue(key);
            } catch (Exception exception) {
                return null;
            }
        }
    }

    @Override
    public boolean exists(K key) {
        synchronized (offsetTable) {
            checkClosed();
            return offsetTable.containsKey(key);
        }
    }

    @Override
    public void write(K key, V value) {
        synchronized (offsetTable) {
            checkClosed();
            offsetTableIsUpdated = true;
            try {
                writeField(key, value);
            } catch (Exception exception) {
                throw new RuntimeException("Error in write");
            }
        }
    }

    @Override
    public void delete(K key) {
        synchronized (offsetTable) {
            offsetTableIsUpdated = true;
            checkClosed();
            ++deletedCounter;
            offsetTable.remove(key);
        }
    }

    @Override
    public Iterator<K> readKeys() {
        synchronized (offsetTable) {
            checkClosed();
            return offsetTable.keySet().iterator();
        }
    }

    @Override
    public int size() {
        synchronized (offsetTable) {
            checkClosed();
            return offsetTable.size();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (offsetTable) {
            checkClosed();
            isClosed = true;

            refreshStorageFile();
            storageFileIO.close();

            if (offsetTableIsUpdated) {
                writeOffsetTable();
                offsetTableIsUpdated = false;
            }
            indexFileIO.close();
        }
    }

    private void checkClosed() {
        if (isClosed) {
            throw new RuntimeException("Storage is closed");
        }
    }

    private void readOffsetTable() throws IOException {
        for (int size = indexFileIO.readSize(); size >= 0; size = indexFileIO.readSize()) {
            offsetTable.put(keySerializer.deserialize(indexFileIO.readField(size)),
                    indexFileIO.readOffset());
        }
    }

    private void writeOffsetTable() throws IOException {
        indexFileIO.clear();
        for (HashMap.Entry<K, Long> entry : offsetTable.entrySet()) {
            indexFileIO.writeSize(keySerializer.sizeOfSerialization(entry.getKey()));
            indexFileIO.writeField(keySerializer.serialize(entry.getKey()));
            indexFileIO.writeOffset(entry.getValue());
        }
        offsetTable.clear();
    }

    private V readValue(K key) throws IOException {
        long offset = offsetTable.get(key);
        if (offset > writtenLength) {
            storageFileIO.flush();
            writtenLength = currentStorageLength - 1;
        }
        int size = storageFileIO.readSize(offset);
        return valueSerializer.deserialize(storageFileIO.readField(size));
    }

    private void writeField(K key, V value) throws IOException {
        currentStorageLength += 4 + keySerializer.sizeOfSerialization(key);
        storageFileIO.streamWriteSize(keySerializer.sizeOfSerialization(key));
        storageFileIO.streamWriteField(keySerializer.serialize(key));

        offsetTable.put(key, currentStorageLength);

        currentStorageLength += 4 + valueSerializer.sizeOfSerialization(value);
        storageFileIO.streamWriteSize(valueSerializer.sizeOfSerialization(value));
        storageFileIO.streamWriteField(valueSerializer.serialize(value));
    }

    private void refreshStorageFile() throws IOException {
        if (deletedCounter > 3 * offsetTable.size()) {
            storageFileIO.enterCopyMode();
            currentStorageLength = 0;
            long oldFileOffset = 0;
            for (int keySize = storageFileIO.copyReadSize();
                 keySize >= 0; keySize = storageFileIO.copyReadSize()) {

                ByteBuffer keyCode = storageFileIO.copyReadField(keySize);
                int valueSize = storageFileIO.copyReadSize();
                ByteBuffer valueCode = storageFileIO.copyReadField(valueSize);
                K key = keySerializer.deserialize(keyCode);

                if (offsetTable.containsKey(key) && offsetTable.get(key)
                        .equals(oldFileOffset)) {
                    storageFileIO.streamWriteSize(keySize);
                    storageFileIO.streamWriteField(keyCode);
                    currentStorageLength += 4 + keySize;
                    offsetTable.put(key, currentStorageLength);
                    storageFileIO.streamWriteSize(valueSize);
                    storageFileIO.streamWriteField(valueCode);
                    currentStorageLength += 4 + valueSize;
                }

                oldFileOffset += 8 + keySize + valueSize;
            }
            storageFileIO.exitCopyMode();
        }
    }
}
