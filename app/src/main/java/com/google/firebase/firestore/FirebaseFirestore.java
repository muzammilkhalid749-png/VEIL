package com.google.firebase.firestore;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.Timestamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FirebaseFirestore {
    private static final String TAG = "FirebaseFirestoreRest";
    private static final String DEFAULT_PROJECT_ID = "veil-2b956";
    private static final String BASE_URL = "https://firestore.googleapis.com/v1/projects/"
            + DEFAULT_PROJECT_ID + "/databases/(default)/documents";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private static final FirebaseFirestore INSTANCE = new FirebaseFirestore();

    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService pollExecutor = Executors.newScheduledThreadPool(8);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build();

    private final ConcurrentHashMap<String, Map<String, Object>> localStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener<DocumentSnapshot>>> docListeners = new ConcurrentHashMap<>();

    public static FirebaseFirestore getInstance() {
        return INSTANCE;
    }

    public CollectionReference collection(String collectionPath) {
        return new CollectionReference(normalizePath(collectionPath));
    }

    public DocumentReference document(String documentPath) {
        return new DocumentReference(normalizePath(documentPath));
    }

    public WriteBatch batch() {
        return new WriteBatch();
    }

    static String normalizePath(String p) {
        if (p == null) return "";
        String t = p.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static String extractDocId(String path) {
        String norm = normalizePath(path);
        int idx = norm.lastIndexOf('/');
        return idx >= 0 ? norm.substring(idx + 1) : norm;
    }

    static String extractParentCollection(String docPath) {
        String norm = normalizePath(docPath);
        int idx = norm.lastIndexOf('/');
        return idx > 0 ? norm.substring(0, idx) : "";
    }

    public Task<DocumentSnapshot> getDocumentAsync(final String docPath) {
        final String normPath = normalizePath(docPath);
        final String docId = extractDocId(normPath);
        final TaskCompletionSource<DocumentSnapshot> tcs = new TaskCompletionSource<>();

        ioExecutor.execute(() -> {
            try {
                String url = BASE_URL + "/" + normPath;
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String bodyStr = resp.body().string();
                        JSONObject json = new JSONObject(bodyStr);
                        Map<String, Object> parsed = parseFirestoreDocumentJson(json);
                        localStore.put(normPath, new HashMap<>(parsed));
                        tcs.trySetResult(new DocumentSnapshot(docId, normPath, parsed, true));
                        return;
                    } else if (resp.code() == 404) {
                        Map<String, Object> cached = localStore.get(normPath);
                        if (cached != null && !cached.isEmpty()) {
                            tcs.trySetResult(new DocumentSnapshot(docId, normPath, cached, true));
                        } else {
                            tcs.trySetResult(new DocumentSnapshot(docId, normPath, Collections.emptyMap(), false));
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "GET fallback to local store for " + normPath + ": " + e.getMessage());
            }

            Map<String, Object> cached = localStore.get(normPath);
            if (cached != null && !cached.isEmpty()) {
                tcs.trySetResult(new DocumentSnapshot(docId, normPath, cached, true));
            } else {
                tcs.trySetResult(new DocumentSnapshot(docId, normPath, Collections.emptyMap(), false));
            }
        });

        return tcs.getTask();
    }

    public Task<Void> setDocumentAsync(final String docPath, final Object data, final boolean merge) {
        final String normPath = normalizePath(docPath);
        final TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();

        ioExecutor.execute(() -> {
            try {
                Map<String, Object> incoming = objectToMap(data);
                Map<String, Object> mergedMap;
                if (merge) {
                    Map<String, Object> existing = localStore.get(normPath);
                    mergedMap = existing != null ? new HashMap<>(existing) : new HashMap<>();
                    mergedMap.putAll(incoming);
                } else {
                    mergedMap = new HashMap<>(incoming);
                }
                localStore.put(normPath, mergedMap);
                notifyDocumentListeners(normPath, new DocumentSnapshot(extractDocId(normPath), normPath, mergedMap, true));

                JSONObject firestoreDoc = new JSONObject();
                firestoreDoc.put("fields", mapToFirestoreFields(incoming));

                StringBuilder urlBuilder = new StringBuilder(BASE_URL).append("/").append(normPath);
                if (merge && !incoming.isEmpty()) {
                    boolean first = true;
                    for (String k : incoming.keySet()) {
                        if (k == null || k.isEmpty()) continue;
                        urlBuilder.append(first ? "?" : "&").append("updateMask.fieldPaths=").append(k);
                        first = false;
                    }
                }

                Request req = new Request.Builder()
                        .url(urlBuilder.toString())
                        .patch(RequestBody.create(JSON_MEDIA, firestoreDoc.toString()))
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject json = new JSONObject(resp.body().string());
                        Map<String, Object> serverData = parseFirestoreDocumentJson(json);
                        if (!serverData.isEmpty()) {
                            localStore.put(normPath, serverData);
                        }
                    }
                } catch (Exception netEx) {
                    Log.d(TAG, "PATCH stored locally for " + normPath + ": " + netEx.getMessage());
                }

                tcs.trySetResult(null);
            } catch (Exception e) {
                tcs.trySetResult(null);
            }
        });

        return tcs.getTask();
    }

    public Task<Void> deleteDocumentAsync(final String docPath) {
        final String normPath = normalizePath(docPath);
        final TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();

        ioExecutor.execute(() -> {
            localStore.remove(normPath);
            notifyDocumentListeners(normPath, new DocumentSnapshot(extractDocId(normPath), normPath, Collections.emptyMap(), false));
            try {
                String url = BASE_URL + "/" + normPath;
                Request req = new Request.Builder().url(url).delete().build();
                try (Response ignored = httpClient.newCall(req).execute()) {
                }
            } catch (Exception ignored) {
            }
            tcs.trySetResult(null);
        });

        return tcs.getTask();
    }

    public Task<QuerySnapshot> queryCollectionAsync(
            final String collectionPath,
            final List<Query.FilterSpec> filters,
            final String orderByField,
            final Query.Direction orderDirection,
            final long limitCount) {

        final String normCollection = normalizePath(collectionPath);
        final TaskCompletionSource<QuerySnapshot> tcs = new TaskCompletionSource<>();

        ioExecutor.execute(() -> {
            Map<String, DocumentSnapshot> resultDocs = new HashMap<>();

            try {
                String url = BASE_URL + "/" + normCollection + "?pageSize=300";
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject root = new JSONObject(resp.body().string());
                        JSONArray docsArr = root.optJSONArray("documents");
                        if (docsArr != null) {
                            for (int i = 0; i < docsArr.length(); i++) {
                                JSONObject docJson = docsArr.optJSONObject(i);
                                if (docJson == null) continue;
                                String fullName = docJson.optString("name", "");
                                String dId = extractDocId(fullName);
                                String dPath = normCollection + "/" + dId;
                                Map<String, Object> fieldsMap = parseFirestoreDocumentJson(docJson);
                                localStore.put(dPath, fieldsMap);
                                DocumentSnapshot snap = new DocumentSnapshot(dId, dPath, fieldsMap, true);
                                if (matchesFilters(snap, filters)) {
                                    resultDocs.put(dId, snap);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Collection query remote fallback: " + e.getMessage());
            }

            for (Map.Entry<String, Map<String, Object>> entry : localStore.entrySet()) {
                String path = entry.getKey();
                if (extractParentCollection(path).equals(normCollection)) {
                    String dId = extractDocId(path);
                    if (!resultDocs.containsKey(dId)) {
                        DocumentSnapshot snap = new DocumentSnapshot(dId, path, entry.getValue(), true);
                        if (matchesFilters(snap, filters)) {
                            resultDocs.put(dId, snap);
                        }
                    }
                }
            }

            List<DocumentSnapshot> list = new ArrayList<>(resultDocs.values());
            if (orderByField != null && !orderByField.isEmpty()) {
                list.sort((a, b) -> {
                    int cmp = compareValues(a.get(orderByField), b.get(orderByField));
                    return orderDirection == Query.Direction.DESCENDING ? -cmp : cmp;
                });
            }

            if (limitCount > 0 && list.size() > limitCount) {
                list = new ArrayList<>(list.subList(0, (int) limitCount));
            }

            tcs.trySetResult(new QuerySnapshot(list));
        });

        return tcs.getTask();
    }

    public ListenerRegistration registerDocumentListener(final String docPath, final EventListener<DocumentSnapshot> listener) {
        if (listener == null) return () -> {};
        final String normPath = normalizePath(docPath);
        docListeners.computeIfAbsent(normPath, k -> new CopyOnWriteArrayList<>()).add(listener);

        final Map<String, String> lastHash = new ConcurrentHashMap<>();
        ScheduledFuture<?> future = pollExecutor.scheduleWithFixedDelay(() -> {
            getDocumentAsync(normPath).addOnSuccessListener(snap -> {
                if (snap == null) return;
                String sig = snap.exists() + ":" + String.valueOf(snap.getData());
                String prev = lastHash.put(normPath, sig);
                if (prev == null || !prev.equals(sig)) {
                    mainHandler.post(() -> {
                        try {
                            listener.onEvent(snap, null);
                        } catch (Exception ignored) {
                        }
                    });
                }
            });
        }, 0, 850, TimeUnit.MILLISECONDS);

        return () -> {
            future.cancel(false);
            CopyOnWriteArrayList<EventListener<DocumentSnapshot>> list = docListeners.get(normPath);
            if (list != null) {
                list.remove(listener);
            }
        };
    }

    public ListenerRegistration registerQueryListener(
            final String collectionPath,
            final List<Query.FilterSpec> filters,
            final String orderByField,
            final Query.Direction orderDirection,
            final long limitCount,
            final EventListener<QuerySnapshot> listener) {
        if (listener == null) return () -> {};

        final Map<String, Map<String, Object>> knownDocs = new ConcurrentHashMap<>();
        final boolean[] firstRun = {true};

        ScheduledFuture<?> future = pollExecutor.scheduleWithFixedDelay(() -> {
            queryCollectionAsync(collectionPath, filters, orderByField, orderDirection, limitCount)
                    .addOnSuccessListener(querySnap -> {
                        if (querySnap == null) return;
                        List<DocumentSnapshot> currentDocs = querySnap.getDocuments();
                        List<DocumentChange> changes = new ArrayList<>();
                        Set<String> seenIds = new HashSet<>();

                        for (DocumentSnapshot doc : currentDocs) {
                            String id = doc.getId();
                            seenIds.add(id);
                            Map<String, Object> curData = doc.getData() != null ? doc.getData() : Collections.emptyMap();
                            Map<String, Object> prevData = knownDocs.get(id);
                            if (prevData == null) {
                                knownDocs.put(id, new HashMap<>(curData));
                                changes.add(new DocumentChange(doc, DocumentChange.Type.ADDED));
                            } else if (!prevData.equals(curData)) {
                                knownDocs.put(id, new HashMap<>(curData));
                                changes.add(new DocumentChange(doc, DocumentChange.Type.MODIFIED));
                            }
                        }

                        Iterator<Map.Entry<String, Map<String, Object>>> it = knownDocs.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<String, Map<String, Object>> entry = it.next();
                            if (!seenIds.contains(entry.getKey())) {
                                DocumentSnapshot removedSnap = new DocumentSnapshot(
                                        entry.getKey(),
                                        normalizePath(collectionPath) + "/" + entry.getKey(),
                                        entry.getValue(),
                                        false);
                                changes.add(new DocumentChange(removedSnap, DocumentChange.Type.REMOVED));
                                it.remove();
                            }
                        }

                        if (firstRun[0] || !changes.isEmpty()) {
                            firstRun[0] = false;
                            final QuerySnapshot diffSnap = new QuerySnapshot(currentDocs, changes);
                            mainHandler.post(() -> {
                                try {
                                    listener.onEvent(diffSnap, null);
                                } catch (Exception ignored) {
                                }
                            });
                        }
                    });
        }, 0, 750, TimeUnit.MILLISECONDS);

        return () -> future.cancel(false);
    }

    private void notifyDocumentListeners(String normPath, DocumentSnapshot snapshot) {
        CopyOnWriteArrayList<EventListener<DocumentSnapshot>> list = docListeners.get(normPath);
        if (list != null && !list.isEmpty()) {
            mainHandler.post(() -> {
                for (EventListener<DocumentSnapshot> l : list) {
                    try {
                        l.onEvent(snapshot, null);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    private static boolean matchesFilters(DocumentSnapshot doc, List<Query.FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (Query.FilterSpec f : filters) {
            Object actual = doc.get(f.field);
            if ("EQUAL".equals(f.op)) {
                if (f.value == null) {
                    if (actual != null) return false;
                } else {
                    if (actual == null) return false;
                    if (!String.valueOf(f.value).equals(String.valueOf(actual))) return false;
                }
            } else if ("GREATER_THAN".equals(f.op)) {
                if (compareValues(actual, f.value) <= 0) return false;
            } else if ("ARRAY_CONTAINS".equals(f.op)) {
                if (actual instanceof List) {
                    boolean found = false;
                    for (Object item : (List<?>) actual) {
                        if (String.valueOf(f.value).equals(String.valueOf(item))) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) return false;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        long millisA = toComparableLong(a);
        long millisB = toComparableLong(b);
        if (millisA != Long.MIN_VALUE && millisB != Long.MIN_VALUE) {
            return Long.compare(millisA, millisB);
        }
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static long toComparableLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
        if (v instanceof Date) return ((Date) v).getTime();
        return Long.MIN_VALUE;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectToMap(Object obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;
        if (obj instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (entry.getKey() == null) continue;
                Object val = entry.getValue();
                if (val instanceof FieldValue) {
                    val = Timestamp.now();
                }
                map.put(String.valueOf(entry.getKey()), val);
            }
            return map;
        }
        Class<?> curr = obj.getClass();
        while (curr != null && curr != Object.class) {
            for (Field f : curr.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                if (f.isAnnotationPresent(Exclude.class)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) {
                        if (v instanceof FieldValue) {
                            v = Timestamp.now();
                        }
                        map.put(f.getName(), v);
                    }
                } catch (Exception ignored) {
                }
            }
            curr = curr.getSuperclass();
        }
        return map;
    }

    private static JSONObject mapToFirestoreFields(Map<String, Object> map) {
        JSONObject fields = new JSONObject();
        if (map == null) return fields;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) continue;
            try {
                fields.put(key, valueToFirestoreValue(entry.getValue()));
            } catch (Exception ignored) {
            }
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject valueToFirestoreValue(Object val) throws Exception {
        JSONObject obj = new JSONObject();
        if (val == null) {
            obj.put("nullValue", JSONObject.NULL);
        } else if (val instanceof FieldValue) {
            obj.put("timestampValue", formatIsoTimestamp(new Date()));
        } else if (val instanceof Boolean) {
            obj.put("booleanValue", val);
        } else if (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte) {
            obj.put("integerValue", String.valueOf(((Number) val).longValue()));
        } else if (val instanceof Float || val instanceof Double) {
            obj.put("doubleValue", ((Number) val).doubleValue());
        } else if (val instanceof Timestamp) {
            obj.put("timestampValue", formatIsoTimestamp(((Timestamp) val).toDate()));
        } else if (val instanceof Date) {
            obj.put("timestampValue", formatIsoTimestamp((Date) val));
        } else if (val instanceof Map) {
            JSONObject mapVal = new JSONObject();
            mapVal.put("fields", mapToFirestoreFields((Map<String, Object>) val));
            obj.put("mapValue", mapVal);
        } else if (val instanceof Iterable) {
            JSONArray values = new JSONArray();
            for (Object item : (Iterable<?>) val) {
                values.put(valueToFirestoreValue(item));
            }
            JSONObject arrVal = new JSONObject();
            arrVal.put("values", values);
            obj.put("arrayValue", arrVal);
        } else {
            obj.put("stringValue", String.valueOf(val));
        }
        return obj;
    }

    private static Map<String, Object> parseFirestoreDocumentJson(JSONObject docJson) {
        Map<String, Object> result = new HashMap<>();
        if (docJson == null) return result;
        JSONObject fields = docJson.optJSONObject("fields");
        if (fields == null) return result;
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject valObj = fields.optJSONObject(k);
            if (valObj != null) {
                result.put(k, parseFirestoreValue(valObj));
            }
        }
        return result;
    }

    private static Object parseFirestoreValue(JSONObject valObj) {
        if (valObj.has("stringValue")) {
            return valObj.optString("stringValue");
        }
        if (valObj.has("booleanValue")) {
            return valObj.optBoolean("booleanValue");
        }
        if (valObj.has("integerValue")) {
            try {
                return Long.parseLong(valObj.optString("integerValue", "0"));
            } catch (Exception e) {
                return 0L;
            }
        }
        if (valObj.has("doubleValue")) {
            return valObj.optDouble("doubleValue", 0.0);
        }
        if (valObj.has("timestampValue")) {
            String iso = valObj.optString("timestampValue", "");
            return parseIsoTimestamp(iso);
        }
        if (valObj.has("mapValue")) {
            JSONObject mapVal = valObj.optJSONObject("mapValue");
            return parseFirestoreDocumentJson(mapVal);
        }
        if (valObj.has("arrayValue")) {
            JSONObject arrVal = valObj.optJSONObject("arrayValue");
            List<Object> list = new ArrayList<>();
            if (arrVal != null) {
                JSONArray values = arrVal.optJSONArray("values");
                if (values != null) {
                    for (int i = 0; i < values.length(); i++) {
                        JSONObject item = values.optJSONObject(i);
                        if (item != null) {
                            list.add(parseFirestoreValue(item));
                        }
                    }
                }
            }
            return list;
        }
        return null;
    }

    private static String formatIsoTimestamp(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date != null ? date : new Date());
    }

    private static Timestamp parseIsoTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) return Timestamp.now();
        try {
            String clean = iso;
            if (clean.endsWith("Z") && clean.contains(".")) {
                int dot = clean.indexOf('.');
                String frac = clean.substring(dot + 1, clean.length() - 1);
                if (frac.length() > 3) {
                    clean = clean.substring(0, dot + 4) + "Z";
                }
            }
            SimpleDateFormat sdf = new SimpleDateFormat(
                    clean.contains(".") ? "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" : "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(clean);
            if (d != null) {
                return new Timestamp(d);
            }
        } catch (Exception ignored) {
        }
        return Timestamp.now();
    }
}
