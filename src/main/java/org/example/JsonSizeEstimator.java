package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

public class JsonSizeEstimator {
    private static final int JSON_SYNTAX_OVERHEAD = 2; // {} or [] brackets
    private static final int FIELD_SYNTAX_OVERHEAD = 3; // quotes and colon
    private static final int COMMA_SEPARATOR = 1;

    private final Map<Class<?>, Integer> typeMinSizes;
    private final Map<Class<?>, Integer> typeMaxSizes;
    private final int maxCollectionSize;
    private final Map<Class<?>, SizeEstimate> calculatedSizes;
    private final Set<Class<?>> currentPath;

    public JsonSizeEstimator(int maxCollectionSize) {
        this.maxCollectionSize = maxCollectionSize;
        this.calculatedSizes = new HashMap<>();
        this.currentPath = new HashSet<>();
        this.typeMaxSizes = initializeTypeMaxSizes();
        this.typeMinSizes = initializeTypeMinSizes();
    }

    private Map<Class<?>, Integer> initializeTypeMaxSizes() {
        Map<Class<?>, Integer> sizes = new HashMap<>();
        // Primitive type maximums
        sizes.put(Byte.class, 1);         // 7
        sizes.put(byte.class, 1);         // 7
        sizes.put(Integer.class, 11);     // -2147483648
        sizes.put(int.class, 11);
        sizes.put(Long.class, 20);        // -9223372036854775808
        sizes.put(long.class, 20);
        sizes.put(Double.class, 24);      // Scientific notation
        sizes.put(double.class, 24);
        sizes.put(Float.class, 15);       // Scientific notation
        sizes.put(float.class, 15);
        sizes.put(Boolean.class, 5);      // false
        sizes.put(boolean.class, 5);
        sizes.put(String.class, 257);     // Configurable default max + 2
        sizes.put(Date.class, 34);        // ISO-8601 format, E.g: "YYYY-MM-DDTHH:mm:ss.ssssss+hh:mm"
        sizes.put(java.time.LocalDate.class, 12);      // "YYYY-MM-DD"
        sizes.put(java.time.LocalDateTime.class, 25);  // "YYYY-MM-DDThh:mm:ss.sss"
        sizes.put(java.time.ZonedDateTime.class, 26);  // "YYYY-MM-DDThh:mm:ss.sssZ"
        sizes.put(java.time.Instant.class, 26);        // "YYYY-MM-DDThh:mm:ss.sssZ"
        sizes.put(UUID.class, 36);        // Standard UUID length
        sizes.put(byte[].class, 1000);    // Configurable default max for binary data
        return sizes;
    }

    private Map<Class<?>, Integer> initializeTypeMinSizes() {
        Map<Class<?>, Integer> sizes = new HashMap<>();
        // Primitive type maximums
        sizes.put(Byte.class, 1);        // 0
        sizes.put(byte.class, 1);        // 7
        sizes.put(Integer.class, 1);     // 0
        sizes.put(int.class, 1);
        sizes.put(Long.class, 1);        // 0
        sizes.put(long.class, 1);
        sizes.put(Double.class, 3);      // 0.0
        sizes.put(double.class, 3);
        sizes.put(Float.class, 3);       // 0.0
        sizes.put(float.class, 3);
        sizes.put(Boolean.class, 4);      // true
        sizes.put(boolean.class, 4);
        sizes.put(String.class, 2);       // ""
        sizes.put(Date.class, 10);        // ISO-8601 format, E.g: "YYYYMMDD"
        sizes.put(java.time.LocalDate.class, 12);      // "YYYY-MM-DD"
        sizes.put(java.time.LocalDateTime.class, 25);  // "YYYY-MM-DDThh:mm:ss.sss"
        sizes.put(java.time.ZonedDateTime.class, 26);  // "YYYY-MM-DDThh:mm:ss.sssZ"
        sizes.put(java.time.Instant.class, 26);        // "YYYY-MM-DDThh:mm:ss.sssZ"
        sizes.put(UUID.class, 36);        // Standard UUID length
        sizes.put(byte[].class, 0);    // Empty array
        return sizes;
    }

    public SizeEstimate estimateSize(Class<?> clazz) {
        // Check if we've already calculated this class
        SizeEstimate cached = calculatedSizes.get(clazz);
        if (cached != null) {
            return cached;
        }

        // Check for circular reference
        if (!currentPath.add(clazz)) {
            return new SizeEstimate(2, 2); // Return minimal {} for circular references
        }

        try {
            // Each object has { }
            int minSize = JSON_SYNTAX_OVERHEAD;
            int maxSize = JSON_SYNTAX_OVERHEAD;

            for (Field field : getAllFields(clazz)) {
                field.setAccessible(true);

                minSize += COMMA_SEPARATOR;
                maxSize += COMMA_SEPARATOR;

                // Add field name size
                int fieldNameSize = field.getName().length() + FIELD_SYNTAX_OVERHEAD;
                minSize += fieldNameSize;
                maxSize += fieldNameSize;

                // Add field value size
                SizeEstimate fieldSize = estimateFieldSize(field);
                minSize += fieldSize.minSize;
                maxSize += fieldSize.maxSize;
            }

            SizeEstimate estimate = new SizeEstimate(minSize, maxSize);
            calculatedSizes.put(clazz, estimate);
            return estimate;
        } finally {
            currentPath.remove(clazz);
        }
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        // Skip reflection for system classes
        if (isSystemClass(current)) {
            return fields;
        }

        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private boolean isSystemClass(Class<?> clazz) {
        return clazz.getModule().getName() != null &&
                clazz.getModule().getName().startsWith("java.");
    }

    private SizeEstimate estimateFieldSize(Field field) {
        Class<?> fieldType = field.getType();

        // Handle system classes directly without reflection
        if (isSystemClass(fieldType)) {
            // Handle collections
            if (Collection.class.isAssignableFrom(fieldType)) {
                return calculateCollectionSize(field);
            }
            // Handle arrays
            if (fieldType.isArray()) {
                return calculateArraySize(fieldType);
            }

            Integer typeMinSize = typeMinSizes.get(fieldType);
            Integer typeMaxSize = typeMaxSizes.get(fieldType);
            if (typeMaxSize != null) {
                return new SizeEstimate(typeMinSize, typeMaxSize);
            }
            // Default size for unknown system classes
            System.err.printf("Unknown type: %s%n", fieldType);
            return new SizeEstimate(2, 100);  // Reasonable default
        }

        // Handle collections
        if (Collection.class.isAssignableFrom(fieldType)) {
            return calculateCollectionSize(field);
        }

        // Handle arrays
        if (fieldType.isArray()) {
            return calculateArraySize(fieldType);
        }

        // Handle primitive types and their wrappers
        Integer typeMaxSize = typeMaxSizes.get(fieldType);
        Integer typeMinSize = typeMinSizes.get(fieldType);
        if (typeMaxSize != null && typeMinSize != null) {
            return new SizeEstimate(typeMinSize, typeMaxSize);
        }

        // For enums, estimate based on longest value
        if (fieldType.isEnum()) {
            return estimateEnumSize(fieldType);
        }

        // For complex objects, recurse
        return estimateSize(fieldType);
    }

    private SizeEstimate calculateArraySize(Class<?> fieldType) {
        Class<?> elementType = fieldType.getComponentType();
        return estimateCollectionSize(elementType);
    }

    private SizeEstimate calculateCollectionSize(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType) {
            Class<?> elementType = (Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[0];
            return estimateCollectionSize(elementType);
        }
        // Raw collection type
        return new SizeEstimate(2, 1000);
    }

    private SizeEstimate estimateCollectionSize(Class<?> elementType) {
        SizeEstimate elementSize = estimateFieldSize(elementType);
        int minSize = JSON_SYNTAX_OVERHEAD; // []
        int maxSize = JSON_SYNTAX_OVERHEAD +
                (maxCollectionSize * (elementSize.maxSize + COMMA_SEPARATOR)) -
                COMMA_SEPARATOR; // Remove last comma
        return new SizeEstimate(minSize, maxSize);
    }

    private SizeEstimate estimateFieldSize(Class<?> fieldType) {
        Integer typeMinSize = typeMinSizes.get(fieldType);
        Integer typeMaxSize = typeMaxSizes.get(fieldType);
        if (typeMaxSize != null && typeMinSize != null) {
            return new SizeEstimate(typeMinSize, typeMaxSize);
        }
        return estimateSize(fieldType);
    }

    private SizeEstimate estimateEnumSize(Class<?> enumClass) {
        Object[] enumConstants = enumClass.getEnumConstants();
        int maxLength = 0;
        int minLength = Integer.MAX_VALUE;
        for (Object constant : enumConstants) {
            maxLength = Math.max(maxLength, constant.toString().length());
            minLength = Math.min(minLength, constant.toString().length());
        }
        return new SizeEstimate(minLength + 2, maxLength + 2); // +2 for quotes
    }

    public static class SizeEstimate {
        public final int minSize;
        public final int maxSize;

        public SizeEstimate(int minSize, int maxSize) {
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        @Override
        public String toString() {
            return String.format("Min: %d bytes, Max: %d bytes", minSize, maxSize);
        }
    }

    // Example usage
    public static void main(String[] args) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        TestClass testClass = new TestClass(new HashSet<>());

        String result = mapper.writeValueAsString(testClass);
        System.out.println(result);
        System.out.println(result.length());

        JsonSizeEstimator estimator = new JsonSizeEstimator(10); // Max 10 items in collections
        SizeEstimate size = estimator.estimateSize(TestClass.class);

        System.out.println("Estimated JSON size: " + size);
    }
}