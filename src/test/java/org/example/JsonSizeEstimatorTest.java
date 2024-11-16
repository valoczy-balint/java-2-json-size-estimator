package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSizeEstimatorTest {
    private JsonSizeEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new JsonSizeEstimator(10); // Default max collection size of 10
    }

    // Test helper classes
    static class SimpleClass {
        private int intField;
        private String stringField;

        public SimpleClass(int intField, String stringField) {
            this.intField = intField;
            this.stringField = stringField;
        }
    }

    static class CollectionClass {
        private List<String> stringList;
        private Set<Integer> intSet;

        public CollectionClass(List<String> stringList, Set<Integer> intSet) {
            this.stringList = stringList;
            this.intSet = intSet;
        }
    }

    static class CircularClass {
        private CircularClass self;
        private String name;

        public CircularClass(String name) {
            this.name = name;
        }
    }

    static class ArrayClass {
        private int[] intArray;
        private String[] stringArray;
        private byte[] byteArray;

        public ArrayClass(int[] intArray, String[] stringArray, byte[] byteArray) {
            this.intArray = intArray;
            this.stringArray = stringArray;
            this.byteArray = byteArray;
        }
    }

    enum TestEnum {
        SMALL, MEDIUM, VERY_VERY_LONG_ENUM_VALUE
    }

    static class EnumClass {
        private TestEnum enumField;

        public EnumClass(TestEnum enumField) {
            this.enumField = enumField;
        }
    }

    record DateTimeClass(
            Date date,
            LocalDate localDate,
            LocalDateTime localDateTime,
            ZonedDateTime zonedDateTime,
            Instant instant
    ) {
    }

    @Test
    void testSimpleClass() {
        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(SimpleClass.class);

        // Expected size calculation:
        // {} = 2 chars
        // "intField": + number (1-11 chars) + comma = 11 + 11 + 1
        // "stringField": + string (2-255 chars) + comma = 14 + 255 + 1
        assertEquals(32, estimate.minSize); // Minimum possible size
        assertEquals(297, estimate.maxSize); // Maximum possible size
    }

    @Test
    void testEmptyCollections() {
        /*
         * {
         *      "stringList": [],
         *      "intSet": [],
         * }
         */
        CollectionClass empty = new CollectionClass(Collections.emptyList(), Collections.emptySet());
        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(CollectionClass.class);

        // Minimum: {"stringList":[],"intSet":[]}
        assertEquals(30, estimate.minSize);
    }

    @Test
    void testMaxSizeCollections() {
        /*
          {                            // 2
               "stringList": [],       // Max: 13 + 2 + (255 + 1) * 10 - 1 + 1 = 2575
               "intSet": [],           // Max: 9 + 2 + (11 + 1) * 10 - 1 + 1 = 131
          }
         */
        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(CollectionClass.class);

        // Max size should account for 10 items in each collection
        // Plus field names, commas, and brackets
        assertTrue(estimate.maxSize >= estimate.minSize);
        assertEquals(2728, estimate.maxSize); // Reasonable upper bound
    }

    @Test
    void testCircularReference() throws JsonProcessingException {
        /*
            {                   // 2
                "self": {},     // Max: 10 + Depends on how it's mapped
                "name": "",     // Max: 263
            }
        */

        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(CircularClass.class);

        // Circular reference should be handled gracefully
        assertEquals(estimate.minSize, 22); // At least "{}"
        assertTrue(estimate.maxSize >= 275);
        assertTrue(estimate.maxSize > estimate.minSize);
    }

    @Test
    void testArrays() {
        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(ArrayClass.class);
        /*
        {                           // Max: 2
            "intArray": [],         // Max: 11 + (11 + 1) * 10 - 1 + 2 + 1 = 133
            "stringArray": [],      // Max: 14 + (255 + 1) * 10 - 1 + 2 + 1 = 2574
            "byteArray": [],        // Max: 12 + (1 + 1) * 10 - 1 + 2 + 1 = 32
        }
         */

        // Arrays should be treated similar to collections
        assertEquals(48, estimate.minSize);
        assertEquals(2765, estimate.maxSize);
    }

    @Test
    void testEnumFields() {
        /*
        {
            "enumField": "VERY_VERY_LONG_ENUM_VALUE",
        }
         */
        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(EnumClass.class);

        // Should account for the longest enum value
        assertEquals(22, estimate.minSize); // Length of shortest enum value + quotes
        assertEquals(42, estimate.maxSize); // Length of longest enum value + quotes
    }

    @Test
    void testDateTimeFields() throws JsonProcessingException {
        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(DateTimeClass.class);
        /*
        {                                                       // 2
            "date": "2023-11-16T15:23:45.123456+05:30",         // 42
            "localDate": "1970-01-01",                          // 25
            "localDateTime": "1970-01-01T00:00:00.000",         // 42
            "zonedDateTime": "1970-01-01T00:00:00.000Z",        // 43
            "instant": "1970-01-01T00:00:00.000Z",              // 37
        }
         */

        // Should account for ISO format lengths
        assertEquals(167, estimate.minSize);
        assertEquals(191, estimate.maxSize); // Approximate sum of all date format lengths
    }

    @Test
    void testNestedObjects() {
        class NestedClass {
            private SimpleClass simple;
            private CollectionClass collection;
        }

        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(NestedClass.class);
        /*
        {
            "simple": {
                "intField": 0,
                "stringField": "",
            },
            "collection": {
                "stringList": [],
                "intSet": [],
            },
        }
         */

        // Should recursively calculate sizes
        assertEquals(88, estimate.minSize);
        assertEquals(3051, estimate.maxSize);
    }

    @Test
    void testInheritance() {
        class ParentClass {
            private String parentField;
        }

        class ChildClass extends ParentClass {
            private String childField;
        }
        /*
        {
            "parentField": "",
            "childField": "",
        }
         */

        JsonSizeEstimator.SizeEstimate estimate = estimator.estimateSize(ChildClass.class);

        // Should include fields from parent class
        assertEquals(35, estimate.minSize);
        assertEquals(545, estimate.maxSize);
    }
}