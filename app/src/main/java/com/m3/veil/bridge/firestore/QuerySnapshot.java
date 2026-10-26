package com.m3.veil.bridge.firestore;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class QuerySnapshot implements Iterable<DocumentSnapshot> {
    private final List<DocumentSnapshot> documents;

    public QuerySnapshot() {
        this.documents = new ArrayList<>();
    }

    public QuerySnapshot(List<DocumentSnapshot> documents) {
        this.documents = documents != null ? documents : new ArrayList<>();
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    public int size() {
        return documents.size();
    }

    public List<DocumentSnapshot> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    public List<DocumentChange> getDocumentChanges() {
        return Collections.emptyList();
    }

    public <T> List<T> toObjects(Class<T> valueType) {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Iterator<DocumentSnapshot> iterator() {
        return documents.iterator();
    }
}
