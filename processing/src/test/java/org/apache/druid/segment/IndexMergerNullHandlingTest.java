/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Sets;
import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.query.DefaultBitmapResultFactory;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.DictionaryEncodedColumn;
import org.apache.druid.segment.data.IncrementalIndexTest;
import org.apache.druid.segment.data.IndexedInts;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.index.semantic.DictionaryEncodedStringValueIndex;
import org.apache.druid.segment.index.semantic.StringValueSetIndexes;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.roaringbitmap.IntIterator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class IndexMergerNullHandlingTest
{
  private IndexMerger indexMerger;
  private IndexIO indexIO;
  private IndexSpec indexSpec;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setUp()
  {
    indexMerger = TestHelper.getTestIndexMergerV9(OffHeapMemorySegmentWriteOutMediumFactory.instance());
    indexIO = TestHelper.getTestIndexIO();
    indexSpec = IndexSpec.DEFAULT;
  }

  @Test
  public void testStringColumnNullHandling() throws Exception
  {
    List<Map<String, Object>> nonNullFlavors = new ArrayList<>();
    nonNullFlavors.add(ImmutableMap.of("d", "a"));
    nonNullFlavors.add(ImmutableMap.of("d", ImmutableList.of("a", "b")));

    List<Map<String, Object>> nullFlavors = new ArrayList<>();
    Map<String, Object> mMissing = ImmutableMap.of();
    Map<String, Object> mEmptyList = ImmutableMap.of("d", Collections.emptyList());
    Map<String, Object> mNull = new HashMap<>();
    mNull.put("d", null);
    Map<String, Object> mEmptyString = ImmutableMap.of("d", "");
    Map<String, Object> mListOfNull = ImmutableMap.of("d", Collections.singletonList(null));
    Map<String, Object> mListOfEmptyString = ImmutableMap.of("d", Collections.singletonList(""));

    nullFlavors.add(mMissing);
    nullFlavors.add(mEmptyList);
    nullFlavors.add(mNull);
    nullFlavors.add(mListOfNull);

    nonNullFlavors.add(mEmptyString);
    nonNullFlavors.add(mListOfEmptyString);

    Set<Map<String, Object>> allValues = new HashSet<>();
    allValues.addAll(nonNullFlavors);
    allValues.addAll(nullFlavors);

    for (Set<Map<String, Object>> subset : Sets.powerSet(allValues)) {
      if (subset.isEmpty()) {
        continue;
      }

      final List<Map<String, Object>> subsetList = new ArrayList<>(subset);

      IncrementalIndex toPersist = IncrementalIndexTest.createIndex(new AggregatorFactory[]{});
      for (Map<String, Object> m : subsetList) {
        toPersist.add(new MapBasedInputRow(0L, ImmutableList.of("d"), m));
      }

      final File tempDir = temporaryFolder.newFolder();
      try (QueryableIndex index = indexIO.loadIndex(indexMerger.persist(toPersist, tempDir, indexSpec, null))) {
        final ColumnHolder columnHolder = index.getColumnHolder("d");

        if (nullFlavors.containsAll(subsetList)) {
          // all null -> should be missing
          Assert.assertNull(subsetList.toString(), columnHolder);
        } else {
          Assert.assertNotNull(subsetList.toString(), columnHolder);

          // The column has multiple values if there are any lists with > 1 element in the input set.
          final boolean hasMultipleValues = subsetList.stream()
                                                      .anyMatch(m -> m.get("d") instanceof List
                                                                     && (((List) m.get("d")).size() > 1));

          // Compute all unique values, the same way that IndexMerger is expected to do it.
          final Set<String> uniqueValues = new HashSet<>();
          for (Map<String, Object> m : subsetList) {
            final List<String> dValues = normalize(m.get("d"));
            uniqueValues.addAll(dValues);

            if (nullFlavors.contains(m)) {
              uniqueValues.add(null);
            }
          }

          try (final DictionaryEncodedColumn<String> dictionaryColumn =
                   (DictionaryEncodedColumn<String>) columnHolder.getColumn()) {
            // Verify unique values against the dictionary.
            Assert.assertEquals(
                subsetList.toString(),
                uniqueValues.stream().sorted(Comparators.naturalNullsFirst()).collect(Collectors.toList()),
                IntStream.range(0, dictionaryColumn.getCardinality())
                         .mapToObj(dictionaryColumn::lookupName)
                         .collect(Collectors.toList())
            );

            Assert.assertEquals(subsetList.toString(), hasMultipleValues, dictionaryColumn.hasMultipleValues());
            Assert.assertEquals(subsetList.toString(), uniqueValues.size(), dictionaryColumn.getCardinality());

            // Verify the expected set of rows was indexed, ignoring order.
            Assert.assertEquals(
                subsetList.toString(),
                ImmutableMultiset.copyOf(
                    subsetList.stream()
                              .map(m -> normalize(m.get("d")))
                              .distinct() // Distinct values only, because we expect rollup.
                              .collect(Collectors.toList())
                ),
                ImmutableMultiset.copyOf(
                    IntStream.range(0, index.getNumRows())
                             .mapToObj(rowNumber -> getRow(dictionaryColumn, rowNumber))
                             // The "distinct" shouldn't be necessary, but it is, because [{}, {d=}, {d=a}]
                             // yields [[null] x 2, [a]] (arguably a bug).
                             .distinct()
                             .collect(Collectors.toList())
                )
            );

            // Verify that the bitmap index for null is correct.
            final DictionaryEncodedStringValueIndex valueIndex = columnHolder.getIndexSupplier().as(
                DictionaryEncodedStringValueIndex.class
            );
            final StringValueSetIndexes valueSetIndex = columnHolder.getIndexSupplier().as(
                StringValueSetIndexes.class
            );

            // Read through the column to find all the rows that should match null.
            final List<Integer> expectedNullRows = new ArrayList<>();
            for (int i = 0; i < index.getNumRows(); i++) {
              final List<String> row = getRow(dictionaryColumn, i);
              if (row.isEmpty() || row.stream().anyMatch(Objects::isNull)) {
                expectedNullRows.add(i);
              }
            }


            if (expectedNullRows.size() > 0) {
              final ImmutableBitmap nullBitmap = valueSetIndex.forValue(null)
                                                              .computeBitmapResult(
                                                                  new DefaultBitmapResultFactory(
                                                                      indexSpec.getBitmapSerdeFactory()
                                                                               .getBitmapFactory()
                                                                  ),
                                                                  false
                                                              );
              final List<Integer> actualNullRows = new ArrayList<>();
              final IntIterator iterator = nullBitmap.iterator();
              while (iterator.hasNext()) {
                actualNullRows.add(iterator.next());
              }

              Assert.assertEquals(subsetList.toString(), expectedNullRows, actualNullRows);
            }
          }
        }
      }
    }
  }

  /**
   * Normalize an input value the same way that IndexMerger is expected to do it.
   */
  private static List<String> normalize(final Object value)
  {
    final List<String> retVal = new ArrayList<>();

    if (value == null) {
      retVal.add(null);
    } else if (value instanceof String) {
      retVal.add((String) value);
    } else if (value instanceof List) {
      final List<String> list = (List<String>) value;
      if (list.isEmpty()) {
        // empty lists become nulls in single valued columns
        // they sometimes also become nulls in multi-valued columns (see comments in getRow())
        retVal.add(null);
      } else {
        retVal.addAll(list);
      }
    } else {
      throw new ISE("didn't expect class[%s]", value.getClass());
    }

    return retVal;
  }

  /**
   * Get a particular row from a column, exactly as reported by the column.
   */
  private static List<String> getRow(final DictionaryEncodedColumn<String> column, final int rowNumber)
  {
    final List<String> retVal = new ArrayList<>();

    if (column.hasMultipleValues()) {
      IndexedInts rowVals = column.getMultiValueRow(rowNumber);
      if (rowVals.size() == 0) {
        // This is a sort of test hack:
        // - If we ingest the subset [{d=[]}, {d=[a, b]}], we get an IndexedInts with 0 size for the nully row,
        //   representing the empty list
        // - If we ingest the subset [{}, {d=[]}, {d=[a, b]}], we instead get an IndexedInts with 1 size,
        //   representing a row with a single null value
        // This occurs because the dimension value comparator used during ingestion considers null and the empty list
        // to be the same.
        // - In the first subset, we only see the empty list and a non-empty list, so the key used in the
        //   incremental index fact table for the nully row is the empty list.
        // - In the second subset, the fact table initially gets an entry for d=null. When the row with the
        //   empty list value is added, it is treated as identical to the first d=null row, so it gets rolled up.
        //   The resulting persisted segment will have [null] instead of [] because of this rollup.
        // To simplify this test class, we always normalize the empty list into null here.
        retVal.add(null);
      } else {
        rowVals.forEach(i -> retVal.add(column.lookupName(i)));
      }
    } else {
      retVal.add(column.lookupName(column.getSingleValueRow(rowNumber)));
    }

    return retVal;
  }
}
