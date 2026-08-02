package com.influencer.dao.shared.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanWrapperImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Copies a dynamic attribute map onto a JavaBean, converting values to each property's type.
 *
 * <p>Extracted from the import hydration service so that the Creator context can apply the same
 * mapping to its own entities without the Campaign context reaching across the boundary to do it.
 * This is generic bean plumbing, not domain logic, which is why it lives in shared rather than in
 * either context.
 */
public final class AttributeBinder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AttributeBinder() {
    }

    public static void applyValues(Object target, Map<String, Object> values) {

        values.forEach((propertyName, rawValue) -> {

            if (rawValue == null) {

                return;

            }



            BeanWrapperImpl wrapper = new BeanWrapperImpl(target);

            if (!wrapper.isWritableProperty(propertyName)) {

                return;

            }



            Class<?> targetType = wrapper.getPropertyType(propertyName);

            if (targetType == null) {

                return;

            }



            wrapper.setPropertyValue(propertyName, convertValue(rawValue, targetType));

        });

    }



    private static Object convertValue(Object rawValue, Class<?> targetType) {

        if (rawValue == null) {

            return null;

        }



        if (targetType.isInstance(rawValue)) {

            return rawValue;

        }



        if (targetType == String.class) {

            if (rawValue instanceof Map || rawValue instanceof List) {

                try {

                    return OBJECT_MAPPER.writeValueAsString(rawValue);

                } catch (Exception exception) {

                    return String.valueOf(rawValue);

                }

            }

            return String.valueOf(rawValue);

        }



        String text = String.valueOf(rawValue).trim();

        if (text.isEmpty()) {

            return null;

        }



        if (targetType == UUID.class) {

            return UUID.fromString(text);

        }

        if (targetType == Integer.class || targetType == int.class) {

            return Integer.valueOf(text);

        }

        if (targetType == Long.class || targetType == long.class) {

            return Long.valueOf(text);

        }

        if (targetType == BigDecimal.class) {

            return new BigDecimal(text);

        }

        if (targetType == Boolean.class || targetType == boolean.class) {

            return Boolean.valueOf(text);

        }

        if (targetType == LocalDate.class) {

            return LocalDate.parse(text);

        }

        if (targetType == Instant.class) {

            return Instant.parse(text);

        }

        if (targetType == String[].class) {

            if (rawValue instanceof List<?> list) {

                return list.stream().map(String::valueOf).toArray(String[]::new);

            }

            if (rawValue.getClass().isArray()) {

                Object[] items = (Object[]) rawValue;

                String[] converted = new String[items.length];

                for (int index = 0; index < items.length; index++) {

                    converted[index] = String.valueOf(items[index]);

                }

                return converted;

            }

            return text.split("\\s*,\\s*");

        }



        return text;

    }

}
