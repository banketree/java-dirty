package uk.co.probablyfine.dirty;

import uk.co.probablyfine.dirty.utils.Classes;
import uk.co.probablyfine.dirty.utils.Types;

import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static uk.co.probablyfine.dirty.utils.Exceptions.unchecked;
import static uk.co.probablyfine.dirty.utils.Nio.fileChannel;
import static uk.co.probablyfine.dirty.utils.Nio.mapFile;

public class Store<T> {

  private final List<MappedByteBuffer> partitions;
  private final MappedByteBuffer sizePartition;
  private final int offSet;
  private final List<Field> fields;
  private final Class<T> klass;
  private final List<BiConsumer<T,Integer>> writeObservers = new ArrayList<>();
  private final int sizePerPartition;
  private final FileChannel fileChannel;
  private int size;

  public Store(String path, Class<T> klass) {
    this.klass = klass;
    this.fields = Classes.primitiveFields(klass).collect(Collectors.toList());
    this.offSet = Types.offSetForClass(klass);
    this.sizePerPartition = 1024 * this.offSet;
    this.fileChannel = fileChannel(path);
    this.partitions = new ArrayList<>();
    this.sizePartition = mapFile(fileChannel, 0, Types.INT.getSize());
    this.size = sizePartition.getInt(0);
    for (int i = 0; i < partitionsForSize(this.size)+1; i++) {
      this.partitions.add(mapFile(fileChannel, Types.INT.getSize()+(i*this.sizePerPartition), sizePerPartition));
    }
  }

  public void put(T t) {
    AtomicInteger currentPosition = new AtomicInteger(this.size * this.offSet);

    if ((this.size*this.offSet)/this.sizePerPartition >= this.partitions.size()) {
      this.partitions.add(mapFile(fileChannel, Types.INT.getSize() + (this.partitions.size()*this.sizePerPartition), sizePerPartition));
    }

    fields.forEach(field -> {
      Object unchecked = unchecked(() -> field.get(t));
      Types fieldType = Types.of(field.getType());
      int partitionIndex = getPartition(currentPosition.get());
      fieldType.getWriteField().accept(partitions.get(partitionIndex), currentPosition.get()-(partitionIndex*this.sizePerPartition), unchecked);
      currentPosition.addAndGet(fieldType.getSize());
    });

    this.writeObservers.forEach(x -> x.accept(t, this.size));
    this.incrementSize();
  }

  private int getPartition(int globalInsertionPosition) {
    return (globalInsertionPosition) / this.sizePerPartition;
  }

  private int partitionsForSize(int numberOfObjects) {
    int totalCapacity = (numberOfObjects * this.offSet) + Types.INT.getSize();
    return totalCapacity/this.sizePerPartition;
  }

  private void incrementSize() {
    this.size++;
    this.sizePartition.putInt(0, this.size);
  }

  public Stream<T> from(int i) {
    Stream.Builder<T> builder = Stream.builder();

    for(int index = i; index < this.size; index++) {
      builder.add(extractEntry(index));
    }

    return builder.build();
  }

  public Stream<T> all() {
    return from(0);
  }

  public Stream<T> reverse() {
    Stream.Builder<T> builder = Stream.builder();
    int index = this.size - 1;
    while(index >= 0) {
      builder.add(extractEntry(index));
      index--;
    }

    return builder.build();
  }

  private T extractEntry(int index) {
    final AtomicInteger cursor = new AtomicInteger(index * this.offSet);
    final T t = unchecked(klass::newInstance);

    fields.forEach(field -> {
      final int partitionIndex = getPartition(cursor.get());
      final MappedByteBuffer partition = partitions.get(partitionIndex);
      final Types fieldType = Types.of(field.getType());
      final Object apply = fieldType.getReadField().apply(partition, cursor.get()-(partitionIndex* sizePerPartition));

      unchecked(() -> field.set(t, apply));

      cursor.addAndGet(fieldType.getSize());
    });
    return t;
  }

  public Optional<T> get(int index) {
    return index >= this.size ? Optional.empty() : ofNullable(extractEntry(index));
  }

  public void observeWrites(BiConsumer<T, Integer> observeWriteFunction) {
    this.writeObservers.add(observeWriteFunction);
  }

  public void reset() {
    this.size = 0;
    this.partitions.clear();
    this.sizePartition.putInt(0, 0);
  }

  public interface WithFile<T> {
    Store<T> from(String path);
  }

  public static <T> WithFile<T> of(final Class<T> fooClass) {
    return path -> new Store<>(path, fooClass);
  }
}
