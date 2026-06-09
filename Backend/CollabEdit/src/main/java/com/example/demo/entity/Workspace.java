package com.example.demo.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class Workspace implements Serializable {
    private final String id;

    // Plain ArrayList — all access is through synchronized methods.
    private final List<byte[]> updates = new ArrayList<>();
    private int updateCount = 0; // plain int, protected by synchronized

    public synchronized void addUpdate(byte[] update) {
        updates.add(update);
        updateCount++;
    }

    public synchronized void compact(byte[] snapshot) {
        updates.clear();
        updates.add(snapshot);
        updateCount = 1;
    }

    public synchronized int getUpdateCount() {
        return updateCount;
    }

    // Returns a snapshot copy — caller gets consistent data
    // and cannot mutate internal state.
    public synchronized List<byte[]> getUpdates() {
        return List.copyOf(updates);
    }
}