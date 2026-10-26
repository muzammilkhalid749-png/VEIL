package com.google.firebase.firestore;

import com.google.firebase.Timestamp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class DocumentSnapshot {
    private final String id;
    private final String path;
    private final Map<String, Object> data;
    private final boolean exists;

    public DocumentSnapshot(String id) {
        this(id, id, null, false);
    }

    public DocumentSnapshot(String id, Map<String, Object> data) {
        this(id, id, data, data != null && !data.isEmpty());
    }

    public DocumentSnapshot(String id, String path, Map<String, Object> data, boolean exists) {
        this.id = extractDocId(id);
        this.path = path != null ? path : (id != null ? id : "");
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        this.exists = exists;
    }

    private static String extractDocId(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int idx = raw.lastIndexOf('/');
        return idx >= 0 && idx + 1 < raw.length() ? raw.substring(idx + 1) : raw;
    }

    public boolean exists() {
        return exists;
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public DocumentReference getReference() {
        return new DocumentReference(path != null && !path.isEmpty() ? path : (id != null ? id : "doc"));
    }

    public boolean contains(String field) {
        return data != null && data.containsKey(field);
    }

    public Map<String, Object> getData() {
        return exists ? Collections.unmodifiableMap(data) : null;
    }

    public Object get(String field) {
        return data != null ? data.get(field) : null;
    }

    public String getString(String field) {
        Object val = get(field);
        return val != null ? String.valueOf(val) : null;
    }

    public Long getLong(String field) {
        Object val = get(field);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try {
                return Long.parseLong((String) val);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public Double getDouble(String field) {
        Object val = get(field);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try {
                return Double.parseDouble((String) val);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public Boolean getBoolean(String field) {
        Object val = get(field);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return null;
    }

    public Timestamp getTimestamp(String field) {
        Object val = get(field);
        if (val instanceof Timestamp) return (Timestamp) val;
        if (val instanceof Date) return new Timestamp((Date) val);
        if (val instanceof Number) return new Timestamp(new Date(((Number) val).longValue()));
        return null;
    }

    public Date getDate(String field) {
        Object val = get(field);
        if (val instanceof Date) return (Date) val;
        if (val instanceof Timestamp) return ((Timestamp) val).toDate();
        if (val instanceof Number) return new Date(((Number) val).longValue());
        return null;
    }

    public <T> T toObject(Class<T> valueType) {
        if (!exists || valueType == null || data == null) {
            return null;
        }
        try {
            T instance = valueType.getDeclaredConstructor().newInstance();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object rawVal = entry.getValue();
                if (key == null || rawVal == null) continue;
                applyProperty(instance, valueType, key, rawVal);
            }
            try {
                Field uidField = findField(valueType, "userId");
                if (uidField != null) {
                    uidField.setAccessible(true);
                    Object currentVal = uidField.get(instance);
                    if (currentVal == null || String.valueOf(currentVal).isEmpty()) {
                        uidField.set(instance, id);
                    }
                }
            } catch (Exception ignored) {
            }
            return instance;
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyProperty(Object target, Class<?> clazz, String key, Object rawVal) {
        Field field = findField(clazz, key);
        if (field != null && !Modifier.isStatic(field.getModifiers())) {
            try {
                field.setAccessible(true);
                Object coerced = coerceValue(rawVal, field.getType());
                if (coerced != null) {
                    field.set(target, coerced);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        String setterName = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                try {
                    Object coerced = coerceValue(rawVal, m.getParameterTypes()[0]);
                    if (coerced != null) {
                        m.invoke(target, coerced);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            try {
                return curr.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            if (name.startsWith("is") && name.length() > 2) {
                String stripped = Character.toLowerCase(name.charAt(2)) + name.substring(3);
                try {
                    return curr.getDeclaredField(stripped);
                } catch (NoSuchFieldException ignored) {
                }
            } else {
                String prefixed = "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                try {
                    return curr.getDeclaredField(prefixed);
                } catch (NoSuchFieldException ignored) {
                }
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    private static Object coerceValue(Object val, Class<?> targetType) {
        if (val == null) return null;
        if (targetType.isAssignableFrom(val.getClass())) {
            return val;
        }
        if (targetType == String.class) {
            return String.valueOf(val);
        }
        if (targetType == long.class || targetType == Long.class) {
            if (val instanceof Number) return ((Number) val).longValue();
            if (val instanceof Timestamp) return ((Timestamp) val).toDate().getTime();
            try {
                return Long.parseLong(String.valueOf(val));
            } catch (Exception ignored) {
                return 0L;
            }
        }
        if (targetType == int.class || targetType == Integer.class) {
            if (val instanceof Number) return ((Number) val).intValue();
            try {
                return Integer.parseInt(String.valueOf(val));
            } catch (Exception ignored) {
                return 0;
            }
        }
        if (targetType == double.class || targetType == Double.class) {
            if (val instanceof Number) return ((Number) val).doubleValue();
            try {
                return Double.parseDouble(String.valueOf(val));
            } catch (Exception ignored) {
                return 0.0;
            }
        }
        if (targetType == float.class || targetType == Float.class) {
            if (val instanceof Number) return ((Number) val).floatValue();
            try {
                return Float.parseFloat(String.valueOf(val));
            } catch (Exception ignored) {
                return 0.0f;
            }
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (val instanceof Boolean) return val;
            if (val instanceof Number) return ((Number) val).intValue() != 0;
            return Boolean.parseBoolean(String.valueOf(val));
        }
        if (targetType == Timestamp.class) {
            if (val instanceof Timestamp) return val;
            if (val instanceof Date) return new Timestamp((Date) val);
            if (val instanceof Number) return new Timestamp(new Date(((Number) val).longValue()));
        }
        if (targetType == Date.class) {
            if (val instanceof Date) return val;
            if (val instanceof Timestamp) return ((Timestamp) val).toDate();
            if (val instanceof Number) return new Date(((Number) val).longValue());
        }
        return null;
    }
}
