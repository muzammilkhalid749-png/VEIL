package com.google.firebase.firestore;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class QuerySnapshot implements Iterable<DocumentSnapshot> {
    private final List<DocumentSnapshot> documents;
    private final List<DocumentChange> documentChanges;

    public QuerySnapshot() {
        this.documents = new ArrayList<>();
        this.documentChanges = new ArrayList<>();
    }

    public QuerySnapshot(List<DocumentSnapshot> documents) {
        this.documents = documents != null ? new ArrayList<>(documents) : new ArrayList<>();
        this.documentChanges = new ArrayList<>();
        for (DocumentSnapshot doc : this.documents) {
            this.documentChanges.add(new DocumentChange(doc, DocumentChange.Type.ADDED));
        }
    }

    public QuerySnapshot(List<DocumentSnapshot> documents, List<DocumentChange> changes) {
        this.documents = documents != null ? new ArrayList<>(documents) : new ArrayList<>();
        this.documentChanges = changes != null ? new ArrayList<>(changes) : new ArrayList<>();
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
        return Collections.unmodifiableList(documentChanges);
    }

    public <T> List<T> toObjects(Class<T> valueType) {
        List<T> result = new ArrayList<>();
        for (DocumentSnapshot doc : documents) {
            T obj = doc.toObject(valueType);
            if (obj != null) {
                result.add(obj);
            }
        }
        return result;
    }

    @NonNull
    @Override
    public Iterator<DocumentSnapshot> iterator() {
        return documents.iterator();
    }
}
